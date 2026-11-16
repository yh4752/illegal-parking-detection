#!/usr/bin/env python3
"""Local-only HTTPS practice stack. No cloud, DNS, host trust-store or existing DB changes."""
import argparse
import base64
import hashlib
import json
import os
import re
import secrets
import shutil
import ssl
import subprocess
import time
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
DEFAULT_STATE = ROOT / '.local-state/https-demo'
PG = 'postgres:17.11@sha256:ff87138949ad9a46f32ff23bd6852b52ce024a13dffe9b4087be98292cdd3b4f'
MQ = 'eclipse-mosquitto:2.0.22@sha256:81a6193ae693942a3231cd3ac52875b02b7e3a19da671b2fb4b55cc874c5b279'
LOCAL_PROJECT = r'parking-(https|o2-[a-f0-9]{8}|monitoring|o3-[a-f0-9]{8}|backup|o4-[a-f0-9]{8})'
CONFIGS = ['infra/deploy/compose.yaml', 'infra/deploy/compose.local.yaml',
           'infra/deploy/init-db.sql', 'infra/deploy/initialize-local.sh', 'infra/deploy/operator.sh',
           'infra/deploy/nginx/default.conf.template', 'infra/deploy/sql/create-roles.sql',
           'infra/deploy/sql/grants.sql', 'infra/deploy/mosquitto/mosquitto.conf', 'infra/deploy/mosquitto/acl']


def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def validate_network_boundary(config, loopback_services=('web',)):
    for name, service in config['services'].items():
        if name not in loopback_services and service.get('ports'):
            raise ValueError('internal_service_port_forbidden:' + name)
        for port in service.get('ports', []):
            if port.get('host_ip') != '127.0.0.1':
                raise ValueError('loopback_binding_required')
    for name in ('data', 'mqtt'):
        if config['networks'][name].get('internal') is not True:
            raise ValueError('internal_network_required:' + name)


def validate_image_bindings(config, images):
    bindings = {'backend': 'backend', 'migrate': 'backend', 'operator': 'backend',
                'initialize-local': 'backend', 'web': 'web', 'postgres': 'postgres',
                'grants': 'postgres', 'mosquitto': 'mqtt', 'simulator': 'simulator'}
    for service, artifact in bindings.items():
        if config['services'].get(service, {}).get('image') != images[artifact]['image_id']:
            raise ValueError('image_binding_mismatch:' + service)


class LocalHttps:
    compose_files = ('infra/deploy/compose.yaml', 'infra/deploy/compose.local.yaml')
    loopback_services = ('web',)
    def __init__(self, state=DEFAULT_STATE):
        self.state = Path(state).resolve()
        local = (ROOT / '.local-state').resolve()
        if not self.state.is_relative_to(local) or self.state == local:
            raise ValueError('state_must_be_below_repository_local_state')
        self.release_path = self.state / 'release.json'
        self.release = json.loads(self.release_path.read_text()) if self.release_path.exists() else None

    def redact(self, text):
        for folder in ('secrets', 'accounts'):
            for file in (self.state / folder).glob('*'):
                if file.is_file():
                    value = file.read_text().strip()
                    if value:
                        text = text.replace(value, '[redacted]')
        return text

    def run(self, *args, input=None, timeout=180, env=None):
        try:
            result = subprocess.run(args, input=input, capture_output=True, text=True,
                                    cwd=ROOT, timeout=timeout, env=env)
        except subprocess.TimeoutExpired:
            raise RuntimeError('Command timed out: ' + args[0]) from None
        if result.returncode:
            raise RuntimeError('Command failed: ' + args[0] + '\n' + self.redact((result.stdout + result.stderr)[-8000:]))
        return result.stdout

    def save_release(self):
        self.release_path.write_text(json.dumps(self.release, ensure_ascii=False, indent=2) + '\n')

    def environment(self, extra=None):
        if not self.release or not re.fullmatch(LOCAL_PROJECT, self.release['project']):
            raise ValueError('local_project_required')
        env = dict(os.environ)
        env.update(self.release['compose_environment'])
        env.update(extra or {})
        return env

    def dc(self, *args, input=None, timeout=180, extra=None):
        return self.run('docker', 'compose', '-p', self.release['project'],
                        *(part for file in self.compose_files for part in ('-f', file)),
                        *args, input=input, timeout=timeout, env=self.environment(extra))

    def check_release(self):
        if not self.release or self.release.get('scope') != 'LOCAL_ONLY':
            raise ValueError('prepare_local_https_first')
        for name, expected in self.release['configuration_sha256'].items():
            if digest(ROOT / name) != expected:
                raise ValueError('configuration_changed_prepare_a_new_isolated_environment:' + name)
        for entry in self.release['images'].values():
            expected = entry['image_id']
            if not re.fullmatch(r'sha256:[a-f0-9]{64}', expected):
                raise ValueError('immutable_image_id_required')
            actual = self.run('docker', 'image', 'inspect', '--format', '{{.Id}}', expected).strip()
            if actual != expected:
                raise ValueError('image_digest_mismatch')
        for name in ('mosquitto.conf', 'acl'):
            if digest(self.state / 'broker' / name) != self.release['configuration_sha256']['infra/deploy/mosquitto/' + name]:
                raise ValueError('mounted_broker_configuration_changed:' + name)
        config = json.loads(self.dc('--profile', '*', 'config', '--format', 'json'))
        validate_network_boundary(config, self.loopback_services)
        validate_image_bindings(config, self.release['images'])

    def write_secret(self, name, value):
        file = self.state / 'secrets' / name
        file.write_text(value)
        # Host parent is 0700. Multiple container UIDs need read access to bind-backed secrets.
        file.chmod(0o644)

    def make_certificates(self):
        pki = self.state / 'pki'
        self.run('openssl', 'req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-days', '7',
                 '-keyout', str(pki / 'ca.key'), '-out', str(pki / 'ca.crt'),
                 '-subj', '/CN=Parking local test CA', '-addext', 'basicConstraints=critical,CA:TRUE',
                 '-addext', 'keyUsage=critical,keyCertSign,cRLSign')
        for name, sans, target in [('web', 'DNS:localhost', self.state / 'web-tls'),
                                   ('mqtt', 'DNS:mosquitto', self.state / 'broker')]:
            key, cert = target / 'server.key', target / 'server.crt'
            self.run('openssl', 'req', '-new', '-newkey', 'rsa:2048', '-nodes',
                     '-keyout', str(key), '-out', str(pki / (name + '.csr')), '-subj', '/CN=' + name)
            ext = pki / (name + '.ext')
            ext.write_text('basicConstraints=critical,CA:FALSE\nkeyUsage=critical,digitalSignature,keyEncipherment\nextendedKeyUsage=serverAuth\nsubjectAltName=' + sans + '\n')
            self.sign_certificate(name, cert)
            if name == 'mqtt':
                key.chmod(0o644)  # broker UID; host parent remains 0700

    def sign_certificate(self, name, destination):
        pki = self.state / 'pki'
        self.run('openssl', 'x509', '-req', '-in', str(pki / (name + '.csr')),
                 '-CA', str(pki / 'ca.crt'), '-CAkey', str(pki / 'ca.key'),
                 '-set_serial', '0x' + secrets.token_hex(16), '-days', '3',
                 '-extfile', str(pki / (name + '.ext')), '-out', str(destination))

    def prepare(self, https_port=18443, http_port=18480, project='parking-https', purpose='learning'):
        if self.release:
            if self.release.get('initialized'):
                self.up()
                return
            raise ValueError('incomplete_preparation_review_state_before_retry; no automatic reset')
        if self.state.exists():
            raise ValueError('state_directory_already_exists; no overwrite')
        if not re.fullmatch(LOCAL_PROJECT, project):
            raise ValueError('local_project_required')
        if not all(1024 <= p <= 65535 for p in (https_port, http_port)) or https_port == http_port:
            raise ValueError('distinct_unprivileged_ports_required')
        # A missing state directory must not allow adoption of pre-existing volumes/containers.
        for resource in ('container', 'volume'):
            if self.run('docker', resource, 'ls', '-q', '--filter', 'label=com.docker.compose.project=' + project).strip():
                raise ValueError('existing_project_resources_require_explicit_review')
        self.state.mkdir(parents=True, mode=0o700)
        for name in ('secrets', 'accounts', 'pki', 'broker', 'web-tls'):
            (self.state / name).mkdir()
        for name in ('admin-password', 'migrator-password', 'runtime-password', 'operator-password',
                     'backup-password', 'mqtt-server-password', 'edge-password'):
            self.write_secret(name, secrets.token_hex(24))
        self.write_secret('cursor-key', base64.b64encode(secrets.token_bytes(32)).decode())
        self.make_certificates()
        for name in ('mosquitto.conf', 'acl'):
            shutil.copy(ROOT / 'infra/deploy/mosquitto' / name, self.state / 'broker' / name)
        passwords = self.state / 'broker/passwords'
        passwords.write_text('parking-server:' + (self.state / 'secrets/mqtt-server-password').read_text() +
                             '\nedge-01:' + (self.state / 'secrets/edge-password').read_text() + '\n')
        images = {}
        for name, ref in [('postgres', PG), ('mqtt', MQ)]:
            try:
                self.run('docker', 'image', 'inspect', ref)
            except RuntimeError:
                self.run('docker', 'pull', ref, timeout=300)
            images[name] = {'source': ref, 'image_id': self.run('docker', 'image', 'inspect', '--format', '{{.Id}}', ref).strip()}
        self.run('docker', 'run', '--rm', '--user', '0:0', '-v', str(self.state / 'broker') + ':/config',
                 images['mqtt']['image_id'], 'mosquitto_passwd', '-U', '/config/passwords')
        passwords.chmod(0o644)
        print('Building local HTTPS backend/web/simulator images...', flush=True)
        for name, dockerfile in [('backend', 'backend/Dockerfile'), ('web', 'frontend/Dockerfile'), ('simulator', 'simulator/Dockerfile')]:
            # Retain a separate reference for each prepared environment. Rebuilding
            # another test must not drop the learning stack's previous image ID.
            tag = 'parking-https-' + name + ':' + project
            self.run('docker', 'build', '-t', tag, '-f', dockerfile, '.', timeout=900)
            images[name] = {'source': dockerfile, 'image_id': self.run('docker', 'image', 'inspect', '--format', '{{.Id}}', tag).strip()}
        viewer_password = self.state / 'accounts/https-viewer.password'
        viewer_password.write_text(secrets.token_urlsafe(24)); viewer_password.chmod(0o644)
        env = {'PARKING_HTTPS_STATE': str(self.state), 'PARKING_HTTPS_PORT': str(https_port),
               'PARKING_HTTPS_HTTP_PORT': str(http_port), 'PARKING_HTTPS_ACCOUNT_PASSWORD_FILE': str(viewer_password)}
        env.update({'PARKING_HTTPS_' + name.upper() + '_IMAGE': entry['image_id'] for name, entry in images.items()})
        sources = self.run('git', 'ls-files', '--cached', '--others', '--exclude-standard').splitlines()
        self.release = {'scope': 'LOCAL_ONLY', 'project': project, 'purpose': purpose, 'initialized': False,
                        'source_commit': self.run('git', 'rev-parse', 'HEAD').strip(),
                        'source_state': 'working tree; includes uncommitted local implementation changes',
                        'source_sha256': {p: digest(ROOT / p) for p in sources if p.startswith(('backend/', 'frontend/', 'simulator/', 'contracts/')) and (ROOT / p).is_file()},
                        'configuration_sha256': {p: digest(ROOT / p) for p in CONFIGS},
                        'images': images, 'compose_environment': env, 'url': 'https://localhost:' + str(https_port),
                        'username': 'https-viewer', 'password_file': str(viewer_password)}
        self.configure_release()
        self.save_release()
        self.check_release()
        self.dc('up', '-d', '--wait', 'postgres', 'mosquitto')
        # The existing initializer rejects non-empty DBs and claims fresh revision initialization.
        self.dc('run', '--rm', 'initialize-local', timeout=120)
        self.dc('run', '--rm', 'grants')
        self.dc('run', '--rm', 'migrate', timeout=120)
        result = self.dc('run', '--rm', 'operator', 'create-user', '--username=https-viewer', '--role=VIEWER', '--zone-id=zone-01')
        self.release['viewer_user_id'] = self.account_result(result)['user_id']
        self.release['initialized'] = True
        self.save_release()
        self.up()

    def configure_release(self):
        """Called once before any application resources start; O3 adds its recorded overlay here."""

    def up(self):
        self.check_release()
        if not self.release['initialized']:
            raise ValueError('new_demo_initialization_required')
        self.dc('up', '-d', 'postgres', 'mosquitto', 'backend', 'web')
        self.wait_ready()

    def wait_ready(self):
        context = ssl.create_default_context(cafile=str(self.state / 'pki/ca.crt'))
        for _ in range(90):
            try:
                with urllib.request.urlopen(self.release['url'] + '/api/csrf', context=context, timeout=2) as response:
                    if response.status == 200 and 'token' in json.load(response):
                        return
            except (OSError, ValueError):
                pass
            time.sleep(1)
        raise RuntimeError('HTTPS_NOT_READY; run local_https.py logs')

    def simulate(self, seconds=8):
        self.check_release()
        return self.dc('run', '--rm', 'simulator', '--host', 'mosquitto', '--port', '8883',
                       '--state', '/state/device.sqlite', '--tls-ca', '/tls/ca.crt', '--username', 'edge-01',
                       '--password-file', '/run/secrets/edge-password', '--seconds', str(seconds), timeout=seconds + 30)

    @staticmethod
    def account_result(output):
        return next(json.loads(line) for line in reversed(output.splitlines()) if line.startswith('{"result":'))

    def account(self, arguments):
        self.check_release()
        extra = None
        if arguments[0] == 'create-user':
            username = next((a.split('=', 1)[1] for a in arguments if a.startswith('--username=')), '')
            if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_.-]{0,63}', username):
                raise ValueError('safe_username_required')
            file = self.state / 'accounts' / (username + '.password')
            with file.open('x') as output:
                output.write(secrets.token_urlsafe(24))
            file.chmod(0o644)
            extra = {'PARKING_HTTPS_ACCOUNT_PASSWORD_FILE': str(file)}
        return self.account_result(self.dc('run', '--rm', 'operator', *arguments, extra=extra))

    def rotate_certificate(self):
        self.check_release()
        cert = self.state / 'web-tls/server.crt'
        replacement = cert.with_suffix('.next')
        previous = cert.read_bytes()
        self.sign_certificate('web', replacement)  # Renewal with same key, different serial.
        self.run('openssl', 'verify', '-CAfile', str(self.state / 'pki/ca.crt'), str(replacement))
        replacement.replace(cert)
        try:
            self.dc('exec', '-T', 'web', 'nginx', '-t')
            self.dc('exec', '-T', 'web', 'nginx', '-s', 'reload')
        except Exception:
            cert.write_bytes(previous)
            raise

    def cleanup_test(self):
        if not self.release or self.release['purpose'] != 'isolated-o2-acceptance' or not self.release['project'].startswith('parking-o2-'):
            raise ValueError('only_owned_acceptance_resources_may_be_removed')
        self.dc('--profile', 'tools', '--profile', 'local-fixture', '--profile', 'simulation',
                'down', '--volumes', '--remove-orphans', timeout=120)
        shutil.rmtree(self.state)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--state', type=Path, default=DEFAULT_STATE)
    parser.add_argument('command', choices=['prepare', 'up', 'stop', 'status', 'logs', 'simulate', 'rotate-certificate', 'account'])
    args, rest = parser.parse_known_args()
    stack = LocalHttps(args.state)
    if args.command == 'prepare':
        stack.prepare()
    elif args.command == 'up':
        stack.up()
    elif args.command == 'stop':
        stack.dc('--profile', 'simulation', 'stop')
        print('STOPPED_DATA_PRESERVED'); return
    elif args.command == 'status':
        print(stack.dc('ps'))
    elif args.command == 'logs':
        print(stack.redact(stack.dc('logs', '--tail', '120', 'backend', 'web', 'mosquitto', 'postgres'))); return
    elif args.command == 'simulate':
        print(stack.simulate()); return
    elif args.command == 'rotate-certificate':
        stack.rotate_certificate(); print('LOCAL_CERTIFICATE_RELOADED'); return
    elif args.command == 'account':
        if not rest:
            raise ValueError('account_operation_required')
        print(json.dumps(stack.account(rest))); return
    print('LOCAL_HTTPS_READY ' + stack.release['url'])
    print('Username: ' + stack.release['username'])
    print('Password file: ' + str(Path(stack.release['password_file']).relative_to(ROOT)))
    print('Test CA: ' + str((stack.state / 'pki/ca.crt').relative_to(ROOT)))


if __name__ == '__main__':
    try:
        main()
    except (ValueError, RuntimeError, OSError) as error:
        raise SystemExit(str(error))
