import json
from pathlib import Path
import tempfile
import unittest
from pyrage import x25519
from server_logs import copy_logs


class LogCopyTest(unittest.TestCase):
    def test_failed_upload_preserves_copy_boundary(self):
        class Database:
            def command(self,*args,output):output.write(b'test-only log')
        class Store:
            def upload(self,*args):raise RuntimeError('unavailable')
        with tempfile.TemporaryDirectory() as tmp:
            marker=Path(tmp)/'last-success.json';original=json.dumps({'copied_through':'2026-11-18T00:00:00+00:00'});marker.write_text(original)
            with self.assertRaises(RuntimeError):copy_logs(Database(),Store(),tmp,str(x25519.Identity.generate().to_public()),'test-key')
            self.assertEqual(marker.read_text(),original)
