"""Encrypted incremental Compose log copies. Failure never advances the copied-through marker."""
import argparse
import datetime as dt
import fcntl
import json
import os
from pathlib import Path
import tempfile
import uuid
from server_backup import ComposeDatabase
from external_backup import BackupStore,seal_files,_new_output
from s3_store import load_client


def copy_logs(db,store,state,recipient,key_ref):
    state=Path(state);state.mkdir(parents=True,exist_ok=True,mode=0o700)
    with (state/'copy.lock').open('a') as lock:
        fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
        marker=state/'last-success.json';previous=json.loads(marker.read_text()) if marker.exists() else None
        end=dt.datetime.now(dt.timezone.utc)
        args=['logs','--no-color','--timestamps','--until',end.isoformat()]
        # First copy includes all logs still retained by Docker. Later copies overlap 30 seconds.
        if previous:
            since=dt.datetime.fromisoformat(previous['copied_through'])-dt.timedelta(seconds=30)
            if since>end:raise ValueError('log_clock_regression')
            args+=['--since',since.isoformat()]
        with tempfile.TemporaryDirectory(dir=state,prefix='logs-') as folder:
            folder=Path(folder);plain=folder/'compose.log'
            with _new_output(plain) as output:db.command(*args,output=output)
            archive=folder/'logs.age'
            seal_files({'compose.log':plain},archive,recipient,{'key_ref':key_ref,'copied_through':end.isoformat(),'source':'Docker retained logs; no claim beyond configured rotation'})
            identifier=end.strftime('%Y%m%dT%H%M%SZ')+'-'+uuid.uuid4().hex[:8]
            result=store.upload(archive,'logs/'+identifier+'.age',key_ref)
        result['copied_through']=end.isoformat()
        with _new_output(state/(identifier+'.json')) as file:file.write((json.dumps(result,indent=2)+'\n').encode())
        temp=state/('success-'+uuid.uuid4().hex+'.tmp')
        with _new_output(temp) as file:file.write(json.dumps(result).encode());file.flush();os.fsync(file.fileno())
        os.replace(temp,marker)
        return result


def main():
    p=argparse.ArgumentParser(description=__doc__)
    for name in ('release','config','credentials-file','state','recipient-file'):p.add_argument('--'+name,type=Path,required=True)
    p.add_argument('--key-ref',required=True);p.add_argument('--confirm-project',choices=['parking-server'],required=True);args=p.parse_args()
    try:
        client,cfg=load_client(args.config,args.credentials_file)
        copy_logs(ComposeDatabase(args.release),BackupStore(client,cfg['bucket'],cfg['prefix'],cfg['owner']),args.state,args.recipient_file.read_text().strip(),args.key_ref)
        print('LOG_CIPHERTEXT_UPLOAD_VERIFIED')
    except Exception:p.exit(1,'LOG_COPY_FAILED_PREVIOUS_MARKER_PRESERVED\n')


if __name__=='__main__':main()
