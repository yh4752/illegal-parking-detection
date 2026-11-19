from pathlib import Path
import datetime as dt
import importlib
import tempfile
import unittest
from unittest.mock import patch
from cryptography import x509
from cryptography.hazmat.primitives import hashes,serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.x509.oid import NameOID


class CertificateStageTest(unittest.TestCase):
    def test_matching_key_name_chain_and_expiry_required_before_staging(self):
        module=importlib.import_module('stage_certificate')
        with tempfile.TemporaryDirectory() as folder:
            folder=Path(folder);key=rsa.generate_private_key(public_exponent=65537,key_size=2048)
            name=x509.Name([x509.NameAttribute(NameOID.COMMON_NAME,'test.invalid')]);now=dt.datetime.now(dt.timezone.utc)
            certificate=(x509.CertificateBuilder().subject_name(name).issuer_name(name).public_key(key.public_key()).serial_number(x509.random_serial_number())
                .not_valid_before(now-dt.timedelta(minutes=1)).not_valid_after(now+dt.timedelta(days=3))
                .add_extension(x509.SubjectAlternativeName([x509.DNSName('test.invalid')]),critical=False)
                .add_extension(x509.BasicConstraints(ca=True,path_length=None),critical=True).sign(key,hashes.SHA256()))
            cert=folder/'chain.pem';cert.write_bytes(certificate.public_bytes(serialization.Encoding.PEM))
            private=folder/'key.pem';private.write_bytes(key.private_bytes(serialization.Encoding.PEM,serialization.PrivateFormat.PKCS8,serialization.NoEncryption()));private.chmod(0o600)
            with self.assertRaises(ValueError):module.stage(cert,private,cert,'wrong.invalid',folder/'wrong')
            self.assertFalse((folder/'wrong').exists())
            module.stage(cert,private,cert,'test.invalid',folder/'staged')
            self.assertEqual((folder/'staged/server.key').stat().st_mode&0o777,0o600)
            with self.assertRaises(FileExistsError):module.stage(cert,private,cert,'test.invalid',folder/'staged')

            original_cert=cert.read_bytes();original_key=private.read_bytes()
            real_run=module.subprocess.run
            def replace_during_verify(*args,**kwargs):
                cert.write_bytes(b'replaced during renewal')
                private.write_bytes(b'replaced during renewal')
                return real_run(*args,**kwargs)
            with patch.object(module.subprocess,'run',side_effect=replace_during_verify):
                module.stage(cert,private,cert,'test.invalid',folder/'snapshot')
            self.assertEqual((folder/'snapshot/server.crt').read_bytes(),original_cert)
            self.assertEqual((folder/'snapshot/server.key').read_bytes(),original_key)
