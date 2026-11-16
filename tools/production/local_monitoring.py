#!/usr/bin/env python3
"""O3 local monitoring; separate DB, certificates and images from the O2 learning stack."""
import argparse
import base64
import json
import secrets
import shutil
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path
from local_https import LocalHttps, ROOT, digest

DEFAULT_STATE = ROOT / '.local-state/monitoring'
IMAGES = {
    'prometheus': 'prom/prometheus:v3.16.0@sha256:e04987641b57704f408e31f6373ba4138ecf27cb4a165d24aabb6c38fddd0f06',
    'grafana': 'infra/monitoring/grafana/Dockerfile',
    'node': 'prom/node-exporter:v1.12.1@sha256:1b4e4438faca4dd7e001dd445d161a4a2091b0fededa84093b3a8dfeae1f1be0',
    'blackbox': 'prom/blackbox-exporter:v0.29.1@sha256:ea1c7aa3ef3f307cd03e85f34abb604478ccb90b567fc690da0cd9776da76b13',
}
CONFIGS = ['infra/monitoring/grafana/Dockerfile', 'infra/monitoring/compose.yaml', 'infra/monitoring/blackbox.yml',
           'infra/monitoring/prometheus/prometheus.yml', 'infra/monitoring/prometheus/alerts.yml',
           'infra/monitoring/grafana/provisioning/datasources/prometheus.yml',
           'infra/monitoring/grafana/provisioning/dashboards/parking.yml',
           'infra/monitoring/grafana/dashboards/parking.json']


def validate_monitoring(config, images):
    if config['networks']['monitoring'].get('internal') is not True:
        raise ValueError('internal_monitoring_network_required')
    for service, artifact in [('prometheus', 'prometheus'), ('grafana', 'grafana'),
                              ('node-exporter', 'node'), ('blackbox', 'blackbox'), ('probe', 'simulator')]:
        definition = config['services'][service]
        if definition['image'] != images[artifact]['image_id']:
            raise ValueError('monitoring_image_binding_mismatch:' + service)
        if definition.get('privileged') or definition.get('network_mode') == 'host':
            raise ValueError('privileged_monitoring_forbidden')
        if any(v.get('source') in ('/', '/var/run/docker.sock') for v in definition.get('volumes', [])):
            raise ValueError('host_root_or_docker_socket_forbidden')


class LocalMonitoring(LocalHttps):
    compose_files = (*LocalHttps.compose_files, 'infra/monitoring/compose.yaml')
    loopback_services = ('web', 'grafana')

    def __init__(self, state=DEFAULT_STATE):
        super().__init__(state)
        self.grafana_port = 13000

    def prepare(self, https_port=19443, http_port=19480, project='parking-monitoring', purpose='learning-monitoring', grafana_port=13000):
        if not 1024 <= grafana_port <= 65535 or grafana_port in (https_port, http_port):
            raise ValueError('distinct_unprivileged_grafana_port_required')
        self.grafana_port = grafana_port
        super().prepare(https_port, http_port, project, purpose)

    def configure_release(self):
        for name in ('monitoring-password', 'grafana-password'):
            self.write_secret(name, secrets.token_urlsafe(36))
        for name, ref in IMAGES.items():
            reference = ref
            if name == 'grafana':
                reference = 'parking-monitoring-grafana:' + self.release['project']
                self.run('docker', 'build', '-t', reference, '-f', ref, '.', timeout=600)
            else:
                try:
                    self.run('docker', 'image', 'inspect', ref)
                except RuntimeError:
                    self.run('docker', 'pull', ref, timeout=600)
            image = self.run('docker', 'image', 'inspect', '--format', '{{.Id}}', reference).strip()
            self.release['images'][name] = {'source': ref, 'image_id': image}
            self.release['compose_environment']['PARKING_MONITORING_' + name.upper() + '_IMAGE'] = image
        self.release['compose_environment']['PARKING_GRAFANA_PORT'] = str(self.grafana_port)
        self.release['monitoring'] = {'grafana_url': 'http://localhost:' + str(self.grafana_port),
                                      'username': 'monitoring-admin',
                                      'password_file': str(self.state / 'secrets/grafana-password')}
        self.release['configuration_sha256'].update({p: digest(ROOT / p) for p in CONFIGS})

    def check_release(self):
        super().check_release()
        if not self.release.get('monitoring'):
            raise ValueError('prepare_monitoring_environment_first')
        config = json.loads(self.dc('--profile', '*', 'config', '--format', 'json'))
        validate_monitoring(config, self.release['images'])

    def check_rules(self):
        self.dc('run', '--rm', '--no-deps', '--entrypoint', '/bin/promtool', 'prometheus',
                'check', 'config', '/etc/prometheus/prometheus.yml')
        self.dc('run', '--rm', '--no-deps', '--entrypoint', '/bin/promtool', 'prometheus',
                'test', 'rules', '/etc/prometheus/rules.test.yml')

    def up(self):
        super().up()
        self.check_rules()
        self.dc('up', '-d', 'prometheus', 'grafana', 'node-exporter', 'blackbox')
        deadline = time.monotonic() + 90
        while time.monotonic() < deadline:
            try:
                if self.grafana('/api/health').get('database') == 'ok' and self.prom('up{job="parking"}') == 1.0:
                    return
            except (OSError, ValueError, KeyError, RuntimeError):
                pass
            time.sleep(1)
        raise RuntimeError('MONITORING_NOT_READY; run local_monitoring.py logs')

    def grafana(self, path):
        settings = self.release['monitoring']
        password = Path(settings['password_file']).read_text().strip()
        auth = base64.b64encode((settings['username'] + ':' + password).encode()).decode()
        request = urllib.request.Request(settings['grafana_url'] + path, headers={'Authorization': 'Basic ' + auth})
        with urllib.request.urlopen(request, timeout=8) as response:
            return json.load(response)

    def prometheus_api(self, path):
        return self.grafana('/api/datasources/proxy/uid/parking-prometheus' + path)

    def prom(self, expression):
        result = self.prometheus_api('/api/v1/query?' + urllib.parse.urlencode({'query': expression}))
        if result['status'] != 'success':
            raise RuntimeError('prometheus_query_failed')
        rows = result['data']['result']
        return float(rows[0]['value'][1]) if len(rows) == 1 else None

    def management(self, path='/actuator/prometheus', authenticated=True, port=9091):
        # Credentials stay inside the one-shot container; they are never CLI arguments.
        code = '''import urllib.request, urllib.error, base64, json
from pathlib import Path
headers={}
if AUTHENTICATED:
    secret=Path('/run/secrets/monitoring-password').read_text().strip()
    headers['Authorization']='Basic '+base64.b64encode(('prometheus:'+secret).encode()).decode()
request=urllib.request.Request(URL, headers=headers)
try: response=urllib.request.urlopen(request, timeout=8)
except urllib.error.HTTPError as error: response=error
print(json.dumps({'status':response.status,'body':response.read().decode()}))
'''.replace('AUTHENTICATED', repr(authenticated)).replace('URL', repr('http://backend:' + str(port) + path))
        return json.loads(self.dc('run', '--rm', '--no-deps', '-T', 'probe', input=code))

    def sql(self, statement):
        return self.dc('exec', '-T', 'postgres', 'psql', '-X', '-v', 'ON_ERROR_STOP=1',
                       '-U', 'postgres', '-d', 'parking_handoff', '-At', input=statement).strip()

    def cleanup_test(self):
        if not self.release or self.release['purpose'] != 'isolated-o3-acceptance' or not self.release['project'].startswith('parking-o3-'):
            raise ValueError('only_owned_o3_acceptance_resources_may_be_removed')
        self.dc('--profile', 'tools', '--profile', 'local-fixture', '--profile', 'simulation',
                'down', '--volumes', '--remove-orphans', timeout=120)
        shutil.rmtree(self.state)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--state', type=Path, default=DEFAULT_STATE)
    parser.add_argument('command', choices=['prepare', 'up', 'stop', 'status', 'logs', 'simulate', 'query', 'rotate-certificate'])
    parser.add_argument('expression', nargs='?', default='up')
    args = parser.parse_args()
    stack = LocalMonitoring(args.state)
    if args.command == 'prepare': stack.prepare()
    elif args.command == 'up': stack.up()
    elif args.command == 'stop':
        stack.dc('--profile', 'simulation', 'stop')
        print('STOPPED_MONITORING_DATA_PRESERVED'); return
    elif args.command == 'status': print(stack.dc('ps'))
    elif args.command == 'logs':
        print(stack.redact(stack.dc('logs', '--tail', '80', 'backend', 'web', 'postgres', 'mosquitto', 'prometheus', 'grafana', 'node-exporter', 'blackbox'))); return
    elif args.command == 'simulate': print(stack.simulate()); return
    elif args.command == 'rotate-certificate':
        stack.rotate_certificate(); print('LOCAL_HTTPS_CERTIFICATE_RELOADED'); return
    elif args.command == 'query':
        print(json.dumps(stack.prometheus_api('/api/v1/query?' + urllib.parse.urlencode({'query': args.expression})), indent=2)); return
    print('LOCAL_MONITORING_READY ' + stack.release['monitoring']['grafana_url'])
    print('HTTPS application: ' + stack.release['url'])
    print('Grafana username: ' + stack.release['monitoring']['username'])
    print('Grafana password file: ' + str(Path(stack.release['monitoring']['password_file']).relative_to(ROOT)))


if __name__ == '__main__':
    try: main()
    except (ValueError, RuntimeError, OSError) as error:
        raise SystemExit(str(error))
