import copy
import unittest
from local_https import validate_network_boundary
from local_monitoring import validate_monitoring


class MonitoringBoundaryTest(unittest.TestCase):
    def configuration(self):
        images = {key: {'image_id': 'sha256:' + str(i) * 64} for i, key in enumerate(
            ('prometheus', 'grafana', 'node', 'blackbox', 'simulator'))}
        mapping = {'prometheus': 'prometheus', 'grafana': 'grafana', 'node-exporter': 'node',
                   'blackbox': 'blackbox', 'probe': 'simulator'}
        config = {'services': {service: {'image': images[key]['image_id']} for service, key in mapping.items()},
                  'networks': {name: {'internal': True} for name in ('monitoring', 'data', 'mqtt')}}
        config['services']['grafana']['ports'] = [{'host_ip': '127.0.0.1', 'target': 3000}]
        return config, images

    def test_only_grafana_and_web_may_bind_loopback(self):
        config, images = self.configuration()
        validate_monitoring(config, images)
        validate_network_boundary(config, ('web', 'grafana'))
        for service in ('prometheus', 'node-exporter', 'blackbox'):
            changed = copy.deepcopy(config)
            changed['services'][service]['ports'] = [{'host_ip': '127.0.0.1', 'target': 9090}]
            with self.assertRaisesRegex(ValueError, 'internal_service_port'):
                validate_network_boundary(changed, ('web', 'grafana'))

    def test_scrape_network_and_exact_image_are_required(self):
        config, images = self.configuration()
        config['networks']['monitoring']['internal'] = False
        with self.assertRaisesRegex(ValueError, 'internal_monitoring_network'):
            validate_monitoring(config, images)
        config['networks']['monitoring']['internal'] = True
        config['services']['grafana']['image'] = 'grafana/grafana:latest'
        with self.assertRaisesRegex(ValueError, 'image_binding_mismatch:grafana'):
            validate_monitoring(config, images)

    def test_exporters_cannot_gain_host_privileges_or_socket(self):
        for override in ({'privileged': True}, {'network_mode': 'host'},
                         {'volumes': [{'source': '/'}]}, {'volumes': [{'source': '/var/run/docker.sock'}]}):
            config, images = self.configuration()
            config['services']['node-exporter'].update(override)
            with self.assertRaisesRegex(ValueError, 'forbidden'):
                validate_monitoring(config, images)
