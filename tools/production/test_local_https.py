import unittest
from local_https import validate_network_boundary, validate_image_bindings


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
