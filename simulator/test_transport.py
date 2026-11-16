import tempfile, unittest
from pathlib import Path
from unittest.mock import Mock
from device import configure_transport

class TransportTest(unittest.TestCase):
    def test_local_mode_does_not_change_auth(self):
        client=Mock();configure_transport(client);self.assertEqual(client.mock_calls,[])
    def test_partial_credentials_fail_before_connect(self):
        with self.assertRaises(ValueError):configure_transport(Mock(),username='edge-01')
    def test_tls_verification_and_password_file(self):
        with tempfile.TemporaryDirectory() as d:
            p=Path(d)/'password';p.write_text('test-only\n')
            client=Mock();configure_transport(client,'ca.crt','edge-01',p)
            client.tls_set.assert_called_once_with(ca_certs='ca.crt')
            client.tls_insecure_set.assert_called_once_with(False)
            client.username_pw_set.assert_called_once_with('edge-01','test-only')
