import importlib
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from pyrage import x25519


class ServerBackupFailureTest(unittest.TestCase):
    def test_dump_failure_leaves_service_stopped_and_last_success_untouched(self):
        from server_backup import backup
        calls=[]
        class Database:
            def command(self,*args,**kwargs):calls.append(args)
            def dump(self,path):raise RuntimeError('failed')
        with tempfile.TemporaryDirectory() as tmp:
            state=Path(tmp);old=state/'last-success.json';old.write_text('previous-success')
            with self.assertRaises(RuntimeError):backup(Database(),None,state,str(x25519.Identity.generate().to_public()),'test-key')
            self.assertEqual(calls,[('stop','web','backend')])
            self.assertEqual(old.read_text(),'previous-success')
            self.assertEqual(list(state.glob('snapshot-*')),[])

    def test_mismatch_never_uploads_or_resumes(self):
        from server_backup import backup
        calls=[]
        class Database:
            def command(self,*args,**kwargs):calls.append(args)
            def dump(self,path):Path(path).write_bytes(b'dump')
            def fingerprints(self):return {'table':'expected'}
            def verify_restore(self,*args):raise ValueError('mismatch')
        with tempfile.TemporaryDirectory() as tmp:
            with self.assertRaises(ValueError):backup(Database(),None,tmp,str(x25519.Identity.generate().to_public()),'test-key')
        self.assertEqual(calls,[('stop','web','backend')])
