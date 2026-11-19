"""Whole application + real S3 adapter/age backup in an isolated LOCAL emulator fixture.

No production credentials, host creation or external S3 endpoint is accepted.
The gateway uses the real adapter with an explicitly injected test-only client.
"""
import json
import argparse
import ipaddress
import os
from pathlib import Path
import secrets
import shutil
import subprocess
import time
import uuid
import boto3
from botocore.config import Config
from botocore.exceptions import BotoCoreError,ClientError
from pyrage import x25519
from local_backup import LocalBackup,ROOT,digest
from local_monitoring import LocalMonitoring
from server_backup import ComposeDatabase,backup
from external_backup import BackupStore,unseal_files
from verify_o4 import wait,login,pending,startup_rejected
from verify_o2 import free_port


class ExternalStack(LocalBackup):
    compose_files=(*LocalBackup.compose_files,'tools/production/fixtures/external-compose.yaml')
    loopback_services=('web','grafana','s3-test')
    backend_jar=None

    def run(self,*args,input=None,timeout=180,env=None):
        if args[:2]!=('docker','build'):
            return super().run(*args,input=input,timeout=timeout,env=env)
        log=self.state/('build-'+args[args.index('-t')+1].split(':')[0]+'.txt')
        if self.backend_jar is not None and args[args.index('-f')+1]=='backend/Dockerfile':
            # Local diagnostic/acceptance may reuse an explicitly built and tested bootJar.
            # CI omits this option and still builds the complete production Dockerfile.
            context=self.state/'backend-runtime';context.mkdir()
            shutil.copyfile(self.backend_jar,context/'app.jar')
            shutil.copyfile(ROOT/'infra/handoff/bootstrap.sh',context/'bootstrap.sh')
            recipe=(ROOT/'backend/Dockerfile').read_text().split('FROM eclipse-temurin:17-jre-jammy',1)[1]
            recipe='FROM eclipse-temurin:17-jre-jammy'+recipe
            recipe=recipe.replace('COPY --from=build /source/backend/build/libs/parking-backend-0.1.0-SNAPSHOT.jar /app/app.jar',
                                  'COPY app.jar /app/app.jar').replace('COPY infra/handoff/bootstrap.sh /app/bootstrap.sh','COPY bootstrap.sh /app/bootstrap.sh')
            assert '--from=build' not in recipe
            (context/'Dockerfile').write_text(recipe)
            args=('docker','build','-t',args[args.index('-t')+1],'-f',str(context/'Dockerfile'),str(context))
        with log.open('w') as output:
            result=subprocess.run([*args[:2],'--progress=plain',*args[2:]],cwd=ROOT,stdout=output,stderr=subprocess.STDOUT,timeout=timeout)
        if result.returncode:raise RuntimeError('fixture_image_build_failed: '+self.redact(log.read_text()[-8000:]))
        return ''

    def configure_release(self):
        LocalMonitoring.configure_release(self)
        if self.backend_jar is not None:
            self.release['local_backend_artifact']={'sha256':digest(self.state/'backend-runtime/app.jar'),
                'mode':'explicit prebuilt bootJar; production runtime stage; full Docker build remains required in CI'}
        dashboards=self.state/'backup-dashboards';dashboards.mkdir()
        for source,target in [('infra/monitoring/grafana/dashboards/parking.json','parking.json'),('infra/backup/grafana.json','backup.json')]:
            shutil.copy(ROOT/source,dashboards/target)
        for name in ('recovery','backup-status'):
            (self.state/name).mkdir();(self.state/name).chmod(0o777) # private host parent; distinct container UID writes fixture
        ledger_dir=self.state/'unused-local-ledger';ledger_dir.mkdir()
        self.write_secret('revision-ledger-token',secrets.token_urlsafe(36))
        tag='parking-external-fixture:'+self.release['project']
        self.run('docker','build','-f','tools/production/fixtures/external.Dockerfile','-t',tag,'.',timeout=600)
        image=self.run('docker','image','inspect','--format','{{.Id}}',tag).strip()
        self.release['images']['ledger']={'image_id':image,'source':'tools/production/fixtures/external.Dockerfile'}
        self.release['compose_environment'].update({'PARKING_LEDGER_ID':str(uuid.uuid4()),'PARKING_LEDGER_DIRECTORY':str(ledger_dir),
            'PARKING_LEDGER_USER':'10001:10001','PARKING_LEDGER_IMAGE':image,'PARKING_RECOVERY_DATABASE':'parking_handoff','PARKING_TEST_S3_PORT':str(free_port())})
        for path in ('infra/backup/compose.yaml','tools/production/fixtures/external-compose.yaml','tools/production/fixtures/external_gateway.py'):
            self.release['configuration_sha256'][path]=digest(ROOT/path)
        # Existing learning stacks may exhaust Docker's automatic subnet pools.
        # Allocate unused small subnets for this fixture without pruning any old network.
        ids=self.run('docker','network','ls','-q').split()
        networks=json.loads(self.run('docker','network','inspect',*ids))
        used=[ipaddress.ip_network(c['Subnet']) for n in networks for c in (n['IPAM']['Config'] or []) if c.get('Subnet')]
        pool=next(ipaddress.ip_network(f'10.238.{n}.0/24') for n in range(180,250)
                  if not any(ipaddress.ip_network(f'10.238.{n}.0/24').overlaps(x) for x in used if x.version==4))
        ipam=self.state/'networks.json'
        ipam.write_text(json.dumps({'networks':{name:{'ipam':{'config':[{'subnet':str(net)}]}}
            for name,net in zip(('web','app','data','mqtt','monitoring','monitoring-ui','ledger','test-storage-ui'),pool.subnets(new_prefix=28))}}))
        self.compose_files=(*self.compose_files,str(ipam))
        self.save_release()
        self.dc('up','-d','s3-test')
        wait('loopback S3 fixture ready',self.s3_ready)
        self.dc('run','--rm','--no-deps','revision-ledger','init')
        self.dc('up','-d','revision-ledger')

    def check_release(self):
        LocalMonitoring.check_release(self)
        if self.release['purpose']!='acceptance-external-stack':raise ValueError('external_test_fixture_required')
        cfg=json.loads(self.dc('--profile','*','config','--format','json'))
        assert cfg['networks']['ledger']['internal'] is True
        for name in ('backend','recovery','initialize-local'):
            assert cfg['services'][name]['environment']['PARKING_REVISION_LEDGER_SCOPE']=='AWS_S3'
        assert cfg['services']['revision-ledger']['image']==self.release['images']['ledger']['image_id']

    def s3(self):
        return boto3.client('s3',endpoint_url='http://127.0.0.1:'+self.release['compose_environment']['PARKING_TEST_S3_PORT'],
            region_name='us-east-1',aws_access_key_id='testing',aws_secret_access_key='testing',
            config=Config(signature_version='s3v4',proxies={},connect_timeout=2,read_timeout=5,retries={'max_attempts':2}))

    def s3_ready(self):
        try:return self.s3().list_buckets().get('Buckets') is not None
        except (BotoCoreError,ClientError):return False

    def db_adapter(self):
        # Use the exact production dump/restore/fingerprint code, with this fixture's Compose prefix.
        adapter=object.__new__(ComposeDatabase)
        adapter.prefix=['docker','compose','-p',self.release['project']]
        for file in self.compose_files:adapter.prefix+=['-f',str(ROOT/file)]
        # ComposeDatabase deliberately uses no ambient fixture configuration. Materialize a private
        # resolved compose file and target it, so only the randomly named project can be touched.
        config=json.loads(self.dc('--profile','*','config','--format','json'))
        path=self.state/'resolved-compose.json';path.write_text(json.dumps(config));path.chmod(0o600)
        adapter.prefix=['docker','compose','-p',self.release['project'],'-f',str(path)]
        return adapter


def run(stack):
    start=time.monotonic();client=login(stack)
    assert client.request('/api/zones/zone-01/status')[0]==200
    assert '"status": "STORED"' in stack.simulate(4) and pending(stack)==[]
    baseline=int(stack.sql('SELECT count(*) FROM event_message'))
    store=BackupStore(stack.s3(),'parking-test-backup','backups','123456789012')
    identity=x25519.Identity.generate();db=stack.db_adapter()
    print('EXTERNAL_PHASE real server backup and all-product-table verification',flush=True)
    receipt=backup(db,store,stack.state/'backup-status',str(identity.to_public()),'ephemeral-integration-key')
    # Test runner UID differs from backend UID on Linux. Publish only this non-secret
    # fixture status file; production timer and backend both use UID 10001.
    (stack.state/'backup-status/last-success.json').chmod(0o444)
    stack.wait_ready();client=login(stack)
    assert client.request('/api/zones/zone-01/status')[0]==200
    wait('external backup gauge',lambda:stack.prom('parking_backup_external_source_restore_valid')==1.0)
    assert stack.prom('parking_backup_status_valid')==0.0
    archive=stack.state/'recovered.age';store.download(receipt,archive)
    restored=stack.state/'unsealed';metadata=unseal_files(archive,restored,identity)
    assert (restored/'RESTORE_COMPLETE.json').is_file()
    assert '"status": "STORED"' in stack.simulate(4) and pending(stack)==[]
    newer=int(stack.sql('SELECT count(*) FROM event_message'));assert newer>baseline
    highest=int(stack.sql('SELECT revision_range_end FROM zone_view'))
    source=stack.database
    stack.quiesce()
    source_before=stack.fingerprints(source)
    name='parking_verify_'+uuid.uuid4().hex[:8]
    stack.create_database(name,(restored/'database.dump').read_bytes())
    assert db.fingerprints(name)==metadata['fingerprints']
    startup_rejected(stack,name,'manifest_db_range_mismatch')
    stack.sql('UPDATE zone_view SET recovery_hold=true',name)
    for entry,target in [('revision-primary.json','/state/revisions.json'),('revision-copy.json','/state-copy/revisions.json')]:
        stack.volume_file(target,(restored/entry).read_bytes())
    # A past DB plus both past revision files must still be refused by the current S3 ledger.
    startup_rejected(stack,name,'revision_ledger_mismatch')
    stack.release['compose_environment']['PARKING_RECOVERY_DATABASE']=name;stack.save_release()
    assert stack.recovery('prepare','--reason=external local integration restore')['result']=='RECOVERY_PREPARED'
    stack.up();client=login(stack)
    assert client.request('/api/zones/zone-01/status')[0]==503
    assert client.request('/api/stream?zone_id=zone-01')[0]==503
    held=stack.simulate(4);assert 'APPLICATION_ACK' not in held and pending(stack)
    assert int(stack.sql('SELECT count(*) FROM event_message'))==baseline
    print('EXTERNAL_PHASE recovery hold, reconciliation and explicit acceptance',flush=True)
    report=stack.state/'device-report.json';report.write_text(pending(stack)[-1][0])
    reconciled=stack.reconcile(report)
    assert reconciled['result']=='RECOVERY_RECONCILED'
    assert stack.accept(reconciled['report_sha256'])['result']=='RECOVERY_ACCEPTED'
    client=login(stack);status,_,zone=client.request('/api/zones/zone-01/status')
    assert status==200 and zone['view_revision']>highest
    conn,response=client.stream();response.close();conn.close()
    retried=stack.simulate(4)
    assert '"status": "STORED"' in retried and '"status": "DUPLICATE"' in retried and pending(stack)==[]
    assert stack.fingerprints(source)==source_before
    print('EXTERNAL_PHASE S3 gateway outage fails closed',flush=True)
    stack.dc('stop','revision-ledger')
    wait('ledger outage blocks reads',lambda:client.request('/api/zones/zone-01/status')[0]==503)
    assert 'APPLICATION_ACK' not in stack.simulate(3) and pending(stack)
    stack.dc('start','revision-ledger');time.sleep(2)
    assert client.request('/api/zones/zone-01/status')[0]==503
    stack.dc('restart','backend');stack.wait_ready();client=login(stack)
    assert client.request('/api/zones/zone-01/status')[0]==200
    assert '"status": "STORED"' in stack.simulate(4) and pending(stack)==[]
    return {'result':'LOCAL_EXTERNAL_FULL_STACK_PASSED','tables':len(metadata['fingerprints']),
        'baseline_events':baseline,'post_backup_acked_events_missing_in_snapshot':newer-baseline,
        'source_database_preserved':True,'external_source_restore_gauge':1,'local_backup_valid_gauge':0,
        'checks':['HTTPS login','real MQTT stored ACK','production server_backup orchestration','all-product-table dump restore equality',
            'age and version-pinned S3 recovery','DB rollback refused','DB and revision pair rollback refused','API/SSE/ingest held',
            'device reconcile and explicit loss acceptance','new revision and SSE/ACK recovery','S3 gateway outage latches closed'],
        'seconds':round(time.monotonic()-start,3),'external_aws_used':False,'whole_host_rto_verified':False,
        'scope':'real product services; test-only loopback S3 injection; not generated production Compose or AWS IAM/TLS acceptance'}


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--backend-jar',type=Path,help='Local only: use an already built/tested bootJar and record its SHA-256; CI builds from source')
    args=parser.parse_args()
    project='parking-o4-'+uuid.uuid4().hex[:8];state=ROOT/'.local-state'/('external-stack-'+project)
    before=set(subprocess.check_output(['docker','ps','-q'],text=True).split())
    stack=ExternalStack(state);result=None
    if args.backend_jar is not None:
        stack.backend_jar=args.backend_jar.resolve(strict=True)
    try:
        stack.prepare(free_port(),free_port(),project,'acceptance-external-stack',grafana_port=free_port())
        result=run(stack)
    except Exception:
        if stack.release:
            for service in ('backend','revision-ledger','postgres','s3-test'):
                try:
                    detail=stack.redact(stack.dc('logs','--no-color','--tail','150',service))
                    (state/('diagnostic-'+service+'.txt')).write_text(detail)
                    print(detail[-6000:],flush=True)
                except Exception:pass
        raise
    finally:
        if stack.release and stack.release['project']==project and stack.release['purpose']=='acceptance-external-stack':
            stack.dc('--profile','*','down','--volumes','--remove-orphans',timeout=120)
        after=set(subprocess.check_output(['docker','ps','-q'],text=True).split())
        if not before<=after:raise RuntimeError('preexisting_running_containers_changed')
    result['existing_running_containers_preserved']=len(before)
    result['images']=stack.release['images']
    result['local_backend_artifact']=stack.release.get('local_backend_artifact')
    (ROOT/'.local-state/external-full-stack-result.json').write_text(json.dumps(result,indent=2)+'\n')
    print(json.dumps(result,indent=2),flush=True)


if __name__=='__main__':main()
