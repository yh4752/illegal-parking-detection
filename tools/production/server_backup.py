"""Explicit quiesced server backup. Failures keep ingestion/web stopped and prior success intact.

Requires an already provisioned, reviewed server bundle. Does not create any
cloud resource or automatically resume after an unverified backup. A successful
source restore check is separate from later independent key/host recovery.
"""
import argparse
import datetime as dt
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import time
import uuid
from external_backup import seal_files, BackupStore, _new_output
from s3_store import load_client


class ComposeDatabase:
    def __init__(self, release):
        self.release=Path(release).resolve()
        config=json.loads((self.release/'compose.json').read_text())
        if config.get('name')!='parking-server': raise ValueError('explicit_parking_server_release_required')
        self.prefix=['docker','compose','--project-directory',str(self.release),'-f',str(self.release/'compose.json'),'-p','parking-server']

    def command(self,*args,output=None,timeout=180):
        result=subprocess.run([*self.prefix,*args],stdout=output or subprocess.PIPE,stderr=subprocess.DEVNULL,timeout=timeout,check=False)
        if result.returncode: raise RuntimeError('server_backup_command_failed')
        return result.stdout if output is None else None

    def sql(self,statement,database='parking_handoff',output=None):
        if not re.fullmatch(r'parking_(handoff|verify_[a-f0-9]{8})',database): raise ValueError('owned_database_required')
        return self.command('exec','-T','postgres','psql','-X','-v','ON_ERROR_STOP=1','-U','postgres','-d',database,'-At','-c',statement,output=output)

    def fingerprints(self,database='parking_handoff'):
        tables=self.sql("SELECT tablename FROM pg_tables WHERE schemaname='public' ORDER BY tablename",database).decode().splitlines()
        result={}
        for table in tables:
            if not re.fullmatch('[a-z_]+',table): raise ValueError('unexpected_table_name')
            with tempfile.TemporaryFile() as rows:
                self.sql('COPY (SELECT row_to_json(t)::text FROM '+table+' t ORDER BY row_to_json(t)::text COLLATE "C") TO STDOUT',database,rows)
                rows.seek(0); checksum=hashlib.file_digest(rows,'sha256').hexdigest()
            result[table]={'rows':int(self.sql('SELECT count(*) FROM '+table,database)), 'sha256':checksum}
        return result

    def dump(self,destination):
        with _new_output(destination) as output:
            self.command('exec','-T','postgres','sh','-c','export PGPASSWORD="$(cat /run/secrets/backup-password)"; exec pg_dump -h 127.0.0.1 -U parking_backup -Fc --no-owner --no-acl parking_handoff',output=output)

    def verify_restore(self,dump,expected):
        name='parking_verify_'+uuid.uuid4().hex[:8]
        self.sql('CREATE DATABASE '+name+' OWNER parking_migrator')
        try:
            with Path(dump).open('rb') as source:
                result=subprocess.run([*self.prefix,'exec','-T','postgres','pg_restore','--exit-on-error','--single-transaction','--no-owner','--no-privileges','--role=parking_migrator','-U','postgres','-d',name],stdin=source,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,timeout=180)
            if result.returncode or self.fingerprints(name)!=expected: raise ValueError('isolated_restore_mismatch')
        finally:
            self.sql('DROP DATABASE '+name)  # Only the fresh random verification DB just created above.


def backup(db,store,state,recipient,key_ref,category="daily"):
    if category not in ("daily","protected"):raise ValueError("backup_category_required")
    state=Path(state);state.mkdir(mode=0o700,parents=True,exist_ok=True)
    with (state/'backup.lock').open('a') as lock:
        fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
        started=time.time()
        db.command('stop','web','backend')
        # Do not restart in finally: a failed snapshot/fence/upload stays visibly closed.
        with tempfile.TemporaryDirectory(dir=state,prefix='snapshot-') as folder:
            folder=Path(folder);dump=folder/'database.dump';db.dump(dump)
            expected=db.fingerprints();db.verify_restore(dump,expected)
            files={'database.dump':dump}
            for name,source in [('revision-primary.json','/state/revisions.json'),('revision-copy.json','/state-copy/revisions.json')]:
                target=folder/name
                with _new_output(target) as output:db.command('run','--rm','-T','--no-deps','--entrypoint','cat','recovery',source,output=output)
                files[name]=target
            if json.loads(files['revision-primary.json'].read_text()) != json.loads(files['revision-copy.json'].read_text()):raise ValueError('revision_pair_mismatch')
            metadata={'key_ref':key_ref,'fingerprints':expected,'backup_started_at':started,'source_database':'parking_handoff',
                      'scope':'quiesced server DB and local revision pair; external ledger must never be restored from this archive'}
            # Capture no private credentials; re-provision secrets explicitly when recovering.
            archive=folder/'snapshot.age';seal_files(files,archive,recipient,metadata)
            db.command('run','--rm','-T','--no-deps','recovery','fence','--reason=verified external backup boundary')
            identifier=dt.datetime.now(dt.timezone.utc).strftime('%Y%m%dT%H%M%SZ')+'-'+uuid.uuid4().hex[:8]
            receipt=store.upload(archive,category+'/'+identifier+'.age',key_ref)
            receipt.update({'source_database_restore_verified':True,'source_table_count':len(expected),'started_at':started,
                            'result':'BACKUP_UPLOADED_SOURCE_RESTORE_VERIFIED','whole_host_recovery_verified':False,'service_readiness_verified':False})
            # Receipt remains even if service restart subsequently fails.
            with _new_output(state/(identifier+'.json')) as output:output.write((json.dumps(receipt,indent=2)+'\n').encode())
        db.command('up','-d','backend','web')
        receipt['completed_at']=time.time()
        temporary=state/('last-success-'+uuid.uuid4().hex+'.tmp')
        with _new_output(temporary) as output:
            output.write((json.dumps(receipt,indent=2)+'\n').encode());output.flush();os.fsync(output.fileno())
        os.replace(temporary,state/'last-success.json')
        return receipt


def main():
    p=argparse.ArgumentParser(description=__doc__)
    for flag in ('release','config','credentials-file','state','recipient-file'):p.add_argument('--'+flag,type=Path,required=True)
    p.add_argument('--category',choices=['daily','protected'],default='daily');p.add_argument('--key-ref',required=True);p.add_argument('--confirm-project',choices=['parking-server'],required=True);args=p.parse_args()
    try:
        client,cfg=load_client(args.config,args.credentials_file)
        result=backup(ComposeDatabase(args.release),BackupStore(client,cfg['bucket'],cfg['prefix'],cfg['owner']),args.state,args.recipient_file.read_text().strip(),args.key_ref,args.category)
        print(json.dumps({'result':result['result'],'source_table_count':result['source_table_count']}))
    except Exception:p.exit(1,'SERVER_BACKUP_FAILED_CHECK_STOPPED_SERVICES_AND_PRIOR_RECEIPTS\n')


if __name__=='__main__':main()
