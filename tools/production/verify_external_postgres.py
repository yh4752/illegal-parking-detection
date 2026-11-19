"""Isolated PostgreSQL dump -> age -> loopback S3 -> download -> new DB restore."""
import json
import logging
import os
from pathlib import Path
import secrets
import subprocess
import sys
import tempfile
import time
import uuid
import boto3
from botocore.config import Config
from moto.server import ThreadedMotoServer
from pyrage import x25519
from server_backup import ComposeDatabase
from external_backup import BackupStore,seal_files,unseal_files

ROOT=Path(__file__).resolve().parents[2]
POSTGRES='postgres:17.11@sha256:ff87138949ad9a46f32ff23bd6852b52ce024a13dffe9b4087be98292cdd3b4f'


def verify():
    (ROOT/'.local-state').mkdir(exist_ok=True)
    started=time.monotonic();project='parking-external-check-'+uuid.uuid4().hex[:8]
    before=set(subprocess.check_output(['docker','ps','-q'],text=True).split())
    logging.getLogger('werkzeug').setLevel(logging.ERROR)
    emulator=ThreadedMotoServer(ip_address='127.0.0.1',port=0,verbose=False);emulator.start()
    try:
        with tempfile.TemporaryDirectory(prefix='external-db-',dir=ROOT/'.local-state') as folder:
            folder=Path(folder);keydir=folder/'secrets';keydir.mkdir(mode=0o700)
            for name in ('admin','migrator','runtime','operator','backup'):
                path=keydir/(name+'-password');path.write_text(secrets.token_urlsafe(32));path.chmod(0o444)  # Host parent is 0700; only this file is mounted for PostgreSQL UID.
            config={'name':'parking-server','services':{'postgres':{'image':POSTGRES,'mem_limit':'512m','cpus':1,
                'environment':{'POSTGRES_DB':'parking_handoff','POSTGRES_PASSWORD_FILE':'/run/secrets/admin-password'},
                'network_mode':'none','tmpfs':['/var/lib/postgresql/data'],
                'volumes':[str(keydir/(name+'-password'))+':/run/secrets/'+name+'-password:ro' for name in ('admin','migrator','runtime','operator','backup')]+[str(ROOT/'infra/deploy/init-db.sql')+':/docker-entrypoint-initdb.d/10-parking.sql:ro',str(ROOT/'infra/deploy/sql/create-roles.sql')+':/opt/parking/create-roles.sql:ro']}}}
            (folder/'compose.json').write_text(json.dumps(config))
            db=ComposeDatabase(folder);db.prefix[-1]=project
            try:
                db.command('up','-d','postgres')
                deadline=time.monotonic()+60
                while True:
                    try:
                        # Only final TCP listener after init scripts counts as ready.
                        db.command('exec','-T','postgres','pg_isready','-h','127.0.0.1','-U','postgres','-d','parking_handoff')
                        db.sql('SELECT 1');break
                    except RuntimeError:
                        if time.monotonic()>deadline:
                            logs=db.command('logs','--no-color','--tail','80','postgres').decode(errors='replace')
                            for secret in keydir.iterdir():logs=logs.replace(secret.read_text(),'[redacted]')
                            raise RuntimeError('isolated_postgres_not_ready: '+logs[-6000:]) from None
                        time.sleep(1)
                db.sql("SET ROLE parking_migrator; CREATE TABLE sample_event (id bigint PRIMARY KEY, payload text NOT NULL); INSERT INTO sample_event SELECT n, '한글 사건 ' || n FROM generate_series(1,147) n; GRANT SELECT ON sample_event TO parking_backup;")
                expected=db.fingerprints();dump=folder/'database.dump';db.dump(dump);db.verify_restore(dump,expected)
                identity=x25519.Identity.generate();archive=folder/'backup.age'
                seal_files({'database.dump':dump},archive,str(identity.to_public()),{'key_ref':'temporary-test-key','fingerprints':expected})
                host,port=emulator.get_host_and_port()
                client=boto3.client('s3',endpoint_url=f'http://{host}:{port}',region_name='us-east-1',aws_access_key_id='testing',aws_secret_access_key='testing',config=Config(signature_version='s3v4',proxies={}))
                client.create_bucket(Bucket='parking-backup-local');client.put_bucket_versioning(Bucket='parking-backup-local',VersioningConfiguration={'Status':'Enabled'})
                store=BackupStore(client,'parking-backup-local','backups','123456789012');receipt=store.upload(archive,'daily/postgres-check.age','temporary-test-key')
                download=folder/'download.age';store.download(receipt,download)
                restored=folder/'unsealed';metadata=unseal_files(download,restored,identity)
                db.verify_restore(restored/'database.dump',metadata['fingerprints'])
                assert db.fingerprints()==expected
                evidence={'result':'LOCAL_EXTERNAL_POSTGRES_ROUNDTRIP_PASSED','rows':147,'tables':1,'fixture':'sample_event, not the complete application database',
                    'dump_bytes':dump.stat().st_size,'encrypted_bytes':archive.stat().st_size,'source_unchanged':True,
                    'source_and_downloaded_dump_restores_verified':True,'external_aws_used':False,'whole_host_rto_verified':False}
            finally:
                db.command('down','--remove-orphans')  # This fresh test project has tmpfs only; no named volumes.
        after=set(subprocess.check_output(['docker','ps','-q'],text=True).split())
        if not before<=after:raise RuntimeError('existing_running_container_changed')
        evidence.update({'existing_running_containers_preserved':len(before),'seconds':round(time.monotonic()-started,3)})
        return evidence
    finally:emulator.stop()


if __name__=='__main__':print(json.dumps(verify(),ensure_ascii=False,indent=2))
