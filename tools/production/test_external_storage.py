"""No AWS credentials or endpoints: deterministic S3 emulator plus signed-client checks."""
import concurrent.futures
import importlib
import json
from pathlib import Path
import sys
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'infra/external-storage'))
sys.path.insert(0, str(ROOT / 'infra/backup'))
import boto3
from moto import mock_aws
from botocore.exceptions import ClientError, ReadTimeoutError
import ledger


class ExternalLedgerTest(unittest.TestCase):
    def setUp(self):
        self.fake = mock_aws(); self.fake.start(); self.addCleanup(self.fake.stop)
        self.client = boto3.client('s3', region_name='us-east-1', aws_access_key_id='testing', aws_secret_access_key='testing')
        self.bucket = 'parking-test-ledger'
        self.client.create_bucket(Bucket=self.bucket)
        self.client.put_bucket_versioning(Bucket=self.bucket, VersioningConfiguration={'Status': 'Enabled'})
        self.identity = 'fd09530c-042e-4368-8fd4-c5ec2bbdd490'
        self.mod = importlib.import_module('ledger_s3')
        self.store = self.mod.S3Ledger(self.client, self.bucket, 'revision/head.json', '123456789012', self.identity)

    def document(self, n):
        return {'format_version': 1, 'zones': {'zone-01': {'zone_id': 'zone-01', 'manifest_generation': n, 'reserved_through': n * 100, 'reserved_at': '2026-11-19T00:00:00Z'}}}

    def request(self, head, n=1):
        return {'expected_version': head['version'], 'expected_sha256': head['sha256'], 'document': self.document(n)}

    def test_missing_is_not_initialized_by_read_and_explicit_init_never_overwrites(self):
        with self.assertRaises(ledger.LedgerError): self.store.head()
        head = self.store.initialize()
        self.assertEqual(head['version'], 0)
        with self.assertRaises(ledger.LedgerError): self.store.initialize()
        self.assertEqual(self.store.head(), head)

    def test_monotonic_cas_and_identical_retry_after_response_loss(self):
        head = self.store.initialize(); request = self.request(head)
        original = self.client.put_object
        def lost(**kwargs):
            original(**kwargs)
            raise ReadTimeoutError(endpoint_url='https://s3.test.invalid')
        with patch.object(self.client, 'put_object', side_effect=lost):
            committed = self.store.append(request)
        self.assertEqual(committed['version'], 1)
        self.assertEqual(self.store.append(request), committed)
        with self.assertRaises(ledger.Conflict): self.store.append(self.request(head, 2))
        with self.assertRaises(ledger.InvalidDocument): self.store.append(self.request(committed, 0))

    def test_retry_must_bind_the_expected_predecessor_hash(self):
        head = self.store.initialize(); request = self.request(head)
        self.store.append(request)
        request['expected_sha256'] = '0' * 64
        with self.assertRaises(ledger.Conflict): self.store.append(request)

    def test_two_writers_only_one_version_wins(self):
        head = self.store.initialize()
        def write(n):
            other = self.mod.S3Ledger(self.client, self.bucket, 'revision/head.json', '123456789012', self.identity)
            try: return other.append(self.request(head, n))['version']
            except ledger.Conflict: return 'conflict'
        with concurrent.futures.ThreadPoolExecutor(2) as pool:
            results = list(pool.map(write, [1, 2]))
        self.assertCountEqual(results, [1, 'conflict'])
        self.assertEqual(self.store.head()['version'], 1)

    def test_identity_corruption_outage_and_disabled_versioning_fail_closed(self):
        self.store.initialize()
        wrong = self.mod.S3Ledger(self.client, self.bucket, 'revision/head.json', '123456789012', 'a95476ba-dcc5-48cf-afd8-1fc910f894ec')
        with self.assertRaises(ledger.LedgerError): wrong.head()
        with patch.object(self.client, 'get_object', side_effect=ClientError({'Error': {'Code': 'AccessDenied'}}, 'GetObject')):
            with self.assertRaises(ledger.LedgerError): self.store.head()
        self.client.put_bucket_versioning(Bucket=self.bucket, VersioningConfiguration={'Status': 'Suspended'})
        with self.assertRaises(ledger.LedgerError): self.store.head()
        self.client.put_bucket_versioning(Bucket=self.bucket, VersioningConfiguration={'Status': 'Enabled'})
        self.client.put_object(Bucket=self.bucket, Key='revision/head.json', Body=b'{}')
        with self.assertRaises(ledger.LedgerError): self.store.head()

    def test_observed_head_rollback_rejected(self):
        self.store.initialize()
        old = self.client.get_object(Bucket=self.bucket, Key='revision/head.json')['Body'].read()
        head = self.store.head(); self.store.append(self.request(head))
        self.client.put_object(Bucket=self.bucket, Key='revision/head.json', Body=old)
        with self.assertRaises(ledger.LedgerError): self.store.head()



class ExplicitClientTest(unittest.TestCase):
    def test_ambient_endpoint_and_credentials_are_not_used(self):
        import tempfile, os
        from s3_store import load_client
        with tempfile.TemporaryDirectory() as tmp:
            cfg=Path(tmp)/'cfg.json';cfg.write_text(json.dumps({'bucket':'parking-test-ledger','prefix':'revision','owner':'123456789012','region':'ap-northeast-2','store_id':'fd09530c-042e-4368-8fd4-c5ec2bbdd490'}))
            keys=Path(tmp)/'keys';keys.write_text('[parking]\naws_access_key_id=testing\naws_secret_access_key=testing\n');keys.chmod(0o600)
            with patch.dict(os.environ, {'AWS_ENDPOINT_URL':'http://example.invalid','AWS_ACCESS_KEY_ID':'ambient','AWS_SECRET_ACCESS_KEY':'ambient'}):
                client,_=load_client(cfg,keys)
            self.assertEqual(client.meta.endpoint_url,'https://s3.ap-northeast-2.amazonaws.com')
            self.assertEqual(client._request_signer._credentials.access_key,'testing')
            keys.chmod(0o644)
            with self.assertRaises(ValueError):load_client(cfg,keys)

    def test_expected_owner_and_conditional_headers_reach_the_sdk(self):
        from botocore.stub import Stubber, ANY
        from ledger_s3 import S3Ledger
        client=boto3.client('s3',region_name='us-east-1',aws_access_key_id='testing',aws_secret_access_key='testing')
        with Stubber(client) as stub:
            stub.add_response('get_bucket_versioning',{'Status':'Enabled'},{'Bucket':'parking-test-ledger','ExpectedBucketOwner':'123456789012'})
            stub.add_client_error('put_object','AccessDenied',expected_params={'Bucket':'parking-test-ledger','Key':'revision/head.json','ExpectedBucketOwner':'123456789012','Body':ANY,'ContentType':'application/json','ServerSideEncryption':'AES256','ChecksumSHA256':ANY,'IfNoneMatch':'*'})
            store=S3Ledger(client,'parking-test-ledger','revision/head.json','123456789012','fd09530c-042e-4368-8fd4-c5ec2bbdd490')
            with self.assertRaises(ledger.LedgerError):store.initialize()
            stub.assert_no_pending_responses()


if __name__ == '__main__': unittest.main()
