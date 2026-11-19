"""TEST ONLY: explicit in-network emulator injection; production endpoint rules stay unchanged."""
import os
import json
from pathlib import Path
import sys
import time
import boto3
from botocore.config import Config
sys.path.insert(0,'/app/external-storage')
from ledger_s3 import S3Ledger, LedgerServer, Handler

client=boto3.client('s3',endpoint_url='http://s3-test:5000',region_name='us-east-1',
    aws_access_key_id='testing',aws_secret_access_key='testing',config=Config(signature_version='s3v4',proxies={}))
class DiagnosticLedger(S3Ledger):
    def head(self):
        started=time.monotonic()
        try:return super().head()
        except Exception as error:
            print(json.dumps({'test_ledger_error':type(error).__name__}),flush=True)
            raise
        finally:
            elapsed=time.monotonic()-started
            if elapsed>0.5:print(json.dumps({'test_ledger_slow_seconds':round(elapsed,3)}),flush=True)

ledger=DiagnosticLedger(client,'parking-test-ledger','revision/head.json','123456789012',os.environ['PARKING_TEST_STORE_ID'])
if sys.argv[1:] == ['init']:
    for attempt in range(30):
        try:client.list_buckets();break
        except Exception:
            if attempt==29:raise
            time.sleep(1)
    for bucket in ('parking-test-ledger','parking-test-backup'):
        client.create_bucket(Bucket=bucket)
        client.put_bucket_versioning(Bucket=bucket,VersioningConfiguration={'Status':'Enabled'})
    ledger.initialize()
    print('TEST_S3_INITIALIZED')
elif sys.argv[1:] == ['serve']:
    ledger.head()
    with LedgerServer(('0.0.0.0',8088),Handler) as server:
        server.ledger=ledger
        server.authorization=b'Bearer '+Path('/run/secrets/revision-ledger-token').read_bytes().strip()
        print('TEST_S3_GATEWAY_READY',flush=True)
        server.serve_forever()
else:raise SystemExit('Explicit test init or serve required')
