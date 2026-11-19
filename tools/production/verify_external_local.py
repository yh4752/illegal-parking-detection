"""Actual loopback HTTP/SigV4/age recovery test. No real AWS calls or credentials."""
import contextlib
import hashlib
import json
import logging
import os
from pathlib import Path
import sys
import tempfile
import threading
import time
import urllib.error
import urllib.request
import uuid
import boto3
from botocore.config import Config
from moto.server import ThreadedMotoServer
from pyrage import x25519
from external_backup import BackupStore, seal_files, unseal_files, sha256_file

ROOT=Path(__file__).resolve().parents[2]
sys.path[:0]=[str(ROOT/'infra/external-storage'),str(ROOT/'infra/backup')]
from ledger_s3 import S3Ledger
from ledger import Handler,LedgerServer


def verify():
    started=time.monotonic()
    logging.getLogger('werkzeug').setLevel(logging.ERROR)
    emulator=ThreadedMotoServer(ip_address='127.0.0.1',port=0,verbose=False);emulator.start()
    try:
        host,port=emulator.get_host_and_port()
        client=boto3.client('s3',endpoint_url=f'http://{host}:{port}',region_name='us-east-1',aws_access_key_id='testing',aws_secret_access_key='testing',config=Config(signature_version='s3v4',proxies={}))
        for bucket in ('parking-local-ledger','parking-local-backup'):
            client.create_bucket(Bucket=bucket)
            client.put_bucket_versioning(Bucket=bucket,VersioningConfiguration={'Status':'Enabled'})
        store=S3Ledger(client,'parking-local-ledger','revision/head.json','123456789012',str(uuid.uuid4()));store.initialize()
        server=LedgerServer(('127.0.0.1',0),Handler);server.ledger=store;server.authorization=b'Bearer '+b'a'*48
        thread=threading.Thread(target=server.serve_forever,daemon=True);thread.start()
        try:
            url=f'http://127.0.0.1:{server.server_port}'
            def request(path,body=None,token='a'*48):
                req=urllib.request.Request(url+path,data=json.dumps(body).encode() if body else None,headers={'Authorization':'Bearer '+token,'Content-Type':'application/json'})
                try:
                    with urllib.request.urlopen(req,timeout=5) as response:return response.status,json.load(response)
                except urllib.error.HTTPError as error:return error.code,json.load(error)
            assert request('/v1/head',token='wrong')[0]==401
            code,head=request('/v1/head');assert code==200 and head['storage']=='AWS_S3'
            doc={'format_version':1,'zones':{'zone-01':{'zone_id':'zone-01','manifest_generation':1,'reserved_through':100,'reserved_at':'2026-11-19T00:00:00Z'}}}
            write={'expected_version':head['version'],'expected_sha256':head['sha256'],'document':doc}
            assert request('/v1/versions',write)[0]==201
            assert request('/v1/versions',write)[0]==201
            head=request('/v1/head')[1]
            assert head['version']==1 and head['document']==doc
            with tempfile.TemporaryDirectory() as tmp:
                tmp=Path(tmp);original=tmp/'source';original.write_bytes(os.urandom(2*1024*1024))
                identity=x25519.Identity.generate();archive=tmp/'snapshot.age'
                seal_files({'payload.bin':original},archive,str(identity.to_public()),{'key_ref':'test-only'})
                backups=BackupStore(client,'parking-local-backup','backups','123456789012')
                receipt=backups.upload(archive,'evidence/local-test.age','test-only')
                downloaded=tmp/'download.age';backups.download(receipt,downloaded)
                restored=tmp/'recovered';unseal_files(downloaded,restored,identity)
                assert sha256_file(restored/'payload.bin')==sha256_file(original)
                assert store.head()['version']==1  # Backup recovery never rolls the revision ledger back.
            # Removing the current object via emulator-admin simulates unavailable/missing storage.
            client.delete_object(Bucket='parking-local-ledger',Key='revision/head.json')
            assert request('/v1/head')[0]==503
            return {'result':'LOCAL_EXTERNAL_STORAGE_PROTOCOL_PASSED','scope':'loopback S3 emulator; no AWS IAM/TLS acceptance',
                    'checks':['bearer authentication','signed S3 HTTP requests','CAS update','identical retry','streaming age round trip','version-pinned download','independent ledger preserved','missing head returns 503'],
                    'seconds':round(time.monotonic()-started,3),'external_aws_used':False,'database_restore_verified':False}
        finally:server.shutdown();server.server_close();thread.join(5)
    finally:emulator.stop()


if __name__=='__main__':print(json.dumps(verify(),indent=2))
