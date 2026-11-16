import unittest
import subprocess
import tempfile
from pathlib import Path
from unittest.mock import patch

from local_https import LocalHttps, validate_network_boundary, validate_image_bindings


class CommandTimeoutTest(unittest.TestCase):
    def test_timeout_retains_progress_and_redacts_secrets_for_bytes_and_text(self):
        with tempfile.TemporaryDirectory() as temporary, patch('local_https.ROOT', Path(temporary)):
            state = Path(temporary) / '.local-state/timeout-test'
            (state / 'secrets').mkdir(parents=True)
            (state / 'secrets/token').write_text('fixture-private-token')
            stack = LocalHttps(state)
            for as_bytes in (True, False):
                with self.subTest(as_bytes=as_bytes):
                    output = 'SIMULATOR_STARTED\nfixture-private-token\n'
                    error_output = 'Waiting for child process\n'
                    if as_bytes:
                        output, error_output = output.encode(), error_output.encode()
                    error = subprocess.TimeoutExpired(['docker'], 34, output=output, stderr=error_output)
                    with patch('local_https.subprocess.run', side_effect=error):
                        with self.assertRaises(RuntimeError) as caught:
                            stack.run('docker', 'compose', timeout=34)
                    message = str(caught.exception)
                    self.assertIn('timed out after 34s', message)
                    self.assertIn('SIMULATOR_STARTED', message)
                    self.assertIn('Waiting for child process', message)
                    self.assertIn('[redacted]', message)
                    self.assertNotIn('fixture-private-token', message)

    def test_timeout_without_output_still_reports_the_deadline(self):
        with tempfile.TemporaryDirectory() as temporary, patch('local_https.ROOT', Path(temporary)):
            stack = LocalHttps(Path(temporary) / '.local-state/timeout-test')
            with patch('local_https.subprocess.run', side_effect=subprocess.TimeoutExpired(['docker'], 34)):
                with self.assertRaisesRegex(RuntimeError, 'Command timed out after 34s: docker'):
                    stack.run('docker', timeout=34)


class NetworkBoundaryTest(unittest.TestCase):
    def configuration(self):
        return {'services': {'web': {'ports': [{'host_ip': '127.0.0.1', 'target': 443}]},
                             'backend': {}, 'postgres': {}, 'mosquitto': {}},
                'networks': {'data': {'internal': True}, 'mqtt': {'internal': True}}}

    def test_loopback_web_and_internal_data_are_accepted(self):
        validate_network_boundary(self.configuration())

    def test_public_or_unspecified_bind_address_is_rejected(self):
        for address in ('0.0.0.0', '::', None):
            config = self.configuration()
            config['services']['web']['ports'][0]['host_ip'] = address
            with self.assertRaisesRegex(ValueError, 'loopback'):
                validate_network_boundary(config)

    def test_backend_and_database_port_publication_is_rejected(self):
        for service in ('backend', 'postgres', 'mosquitto'):
            config = self.configuration()
            config['services'][service]['ports'] = [{'host_ip': '127.0.0.1', 'target': 5432}]
            with self.assertRaisesRegex(ValueError, 'internal_service_port'):
                validate_network_boundary(config)

    def test_data_network_must_remain_internal(self):
        config = self.configuration()
        config['networks']['data']['internal'] = False
        with self.assertRaisesRegex(ValueError, 'internal_network'):
            validate_network_boundary(config)

    def test_compose_cannot_substitute_an_unrecorded_image(self):
        images = {name: {'image_id': 'sha256:' + str(i) * 64} for i, name in enumerate(
            ('backend', 'web', 'postgres', 'mqtt', 'simulator'))}
        bindings = {'backend': 'backend', 'migrate': 'backend', 'operator': 'backend',
                    'initialize-local': 'backend', 'web': 'web', 'postgres': 'postgres',
                    'grants': 'postgres', 'mosquitto': 'mqtt', 'simulator': 'simulator'}
        config = {'services': {service: {'image': images[image]['image_id']} for service, image in bindings.items()}}
        validate_image_bindings(config, images)
        config['services']['backend']['image'] = 'other-backend:latest'
        with self.assertRaisesRegex(ValueError, 'image_binding_mismatch:backend'):
            validate_image_bindings(config, images)
