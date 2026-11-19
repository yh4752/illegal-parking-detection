import importlib
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import boto3
from moto import mock_aws
from pyrage import x25519


class ExternalBackupTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(); self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.module = importlib.import_module('external_backup')
        self.identity = x25519.Identity.generate()
        self.source = self.root / 'database.dump'; self.source.write_bytes(b'PGDMP-example\0' * 100)

    def seal(self):
        archive = self.root / 'backup.age'
        self.module.seal_files({'database.dump': self.source}, archive, str(self.identity.to_public()), {'key_ref': 'test-key'})
        return archive

    def test_streaming_archive_round_trip_and_no_overwrite(self):
        archive = self.seal()
        output = self.root / 'restored'
        metadata = self.module.unseal_files(archive, output, self.identity)
        self.assertEqual((output / 'database.dump').read_bytes(), self.source.read_bytes())
        self.assertEqual(metadata['key_ref'], 'test-key')
        self.assertTrue((output / 'RESTORE_COMPLETE.json').is_file())
        with self.assertRaises(FileExistsError): self.module.unseal_files(archive, output, self.identity)
        with self.assertRaises(FileExistsError): self.module.seal_files({'database.dump': self.source}, archive, str(self.identity.to_public()), {})

    def test_wrong_key_tampering_and_truncation_leave_no_restore_directory(self):
        archive = self.seal()
        with self.assertRaises(Exception): self.module.unseal_files(archive, self.root/'wrong', x25519.Identity.generate())
        self.assertFalse((self.root/'wrong').exists())
        raw = archive.read_bytes()
        for n, data in enumerate((raw[:-20], raw[:-1] + bytes([raw[-1] ^ 1]))):
            bad = self.root/f'bad{n}.age'; bad.write_bytes(data)
            with self.assertRaises(Exception): self.module.unseal_files(bad, self.root/f'out{n}', self.identity)
            self.assertFalse((self.root/f'out{n}').exists())

    def test_unsafe_names_and_symlinks_rejected(self):
        for name in ('../escape', '/absolute', 'manifest.json', 'RESTORE_COMPLETE.json', 'a/../b'):
            with self.assertRaises(ValueError): self.module.seal_files({name:self.source}, self.root/'bad.age', str(self.identity.to_public()), {})
        link = self.root/'link'; link.symlink_to(self.source)
        with self.assertRaises(ValueError): self.module.seal_files({'database.dump':link}, self.root/'bad.age', str(self.identity.to_public()), {})

    def test_file_larger_than_old_128_mib_limit(self):
        with self.source.open('wb') as file: file.truncate(129 * 1024 * 1024)
        archive = self.seal()
        output = self.root/'large'
        self.module.unseal_files(archive, output, self.identity)
        self.assertEqual(self.module.sha256_file(output/'database.dump'), self.module.sha256_file(self.source))

    @mock_aws
    def test_create_only_upload_pinned_download_and_corrupt_receipt(self):
        client = boto3.client('s3', region_name='us-east-1', aws_access_key_id='testing', aws_secret_access_key='testing')
        client.create_bucket(Bucket='parking-backup-test')
        client.put_bucket_versioning(Bucket='parking-backup-test', VersioningConfiguration={'Status':'Enabled'})
        archive = self.seal()
        store = self.module.BackupStore(client, 'parking-backup-test', 'backups', '123456789012')
        receipt = store.upload(archive, 'daily/run1.age', 'test-key')
        self.assertEqual(receipt['result'], 'UPLOADED_VERIFIED_CIPHERTEXT')
        with self.assertRaises(Exception): store.upload(archive, 'daily/run1.age', 'test-key')
        # A privileged out-of-band overwrite cannot silently change this recovery reference.
        client.put_object(Bucket='parking-backup-test', Key='backups/daily/run1.age', Body=b'bad')
        copy = self.root/'download.age'; store.download(receipt, copy)
        self.assertEqual(copy.read_bytes(), archive.read_bytes())
        receipt['ciphertext_sha256']='0'*64
        with self.assertRaises(ValueError): store.download(receipt, self.root/'corrupt.age')
        self.assertFalse((self.root/'corrupt.age').exists())
        with self.assertRaises(FileExistsError): store.download(receipt, copy)


if __name__ == '__main__': unittest.main()
