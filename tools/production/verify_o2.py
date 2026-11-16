#!/usr/bin/env python3
"""O2 acceptance through real TLS, Nginx, runtime backend and isolated PostgreSQL/MQTT."""
import concurrent.futures
import hashlib
import http.client
import http.cookiejar
import json
import socket
import ssl
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from pathlib import Path
from local_https import LocalHttps, ROOT, validate_network_boundary


def free_port():
    with socket.socket() as sock:
        sock.bind(('127.0.0.1', 0))
        return sock.getsockname()[1]


class Client:
    def __init__(self, stack):
        self.url = stack.release['url']
        self.context = ssl.create_default_context(cafile=str(stack.state / 'pki/ca.crt'))
        self.cookies = http.cookiejar.CookieJar()
        self.opener = urllib.request.build_opener(urllib.request.HTTPSHandler(context=self.context),
                                                  urllib.request.HTTPCookieProcessor(self.cookies))

    def request(self, path, method='GET', body=None, token=None):
        headers = {'Accept': 'application/json'}
        if body is not None:
            headers['Content-Type'] = 'application/json'
        if token:
            headers['X-CSRF-TOKEN'] = token
        request = urllib.request.Request(self.url + path, headers=headers, method=method,
                                         data=json.dumps(body).encode() if body is not None else None)
        try:
            response = self.opener.open(request, timeout=8)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            raw = response.read()
            try:
                payload = json.loads(raw)
            except ValueError:
                payload = raw.decode()
            return response.status, response.headers, payload

    def login(self, username, password):
        status, headers, csrf = self.request('/api/csrf')
        assert status == 200
        cookie = next(c for c in self.cookies if c.name == 'PARKING_HTTPS_SESSION')
        before = cookie.value
        assert cookie.secure and 'HttpOnly' in cookie._rest and cookie._rest.get('SameSite') == 'Lax'
        status, _, _ = self.request('/api/auth/login', 'POST', {'username': username, 'password': password}, csrf['token'])
        assert status == 200
        assert next(c for c in self.cookies if c.name == 'PARKING_HTTPS_SESSION').value != before

    def stream(self):
        uri = urllib.parse.urlsplit(self.url)
        request = urllib.request.Request(self.url + '/api/stream?zone_id=zone-01')
        self.cookies.add_cookie_header(request)
        connection = http.client.HTTPSConnection(uri.hostname, uri.port, context=self.context, timeout=10)
        connection.request('GET', '/api/stream?zone_id=zone-01', headers=dict(request.header_items()))
        response = connection.getresponse()
        assert response.status == 200 and response.getheader('Content-Type').startswith('text/event-stream')
        return connection, response


def snapshot(response):
    while True:
        line = response.readline()
        assert line, 'SSE ended before a snapshot'
        if line.startswith(b'data: '):
            return json.loads(line[6:])


def certificate_hash(stack):
    port = int(stack.release['compose_environment']['PARKING_HTTPS_PORT'])
    context = ssl.create_default_context(cafile=str(stack.state / 'pki/ca.crt'))
    with socket.create_connection(('127.0.0.1', port), timeout=5) as raw:
        with context.wrap_socket(raw, server_hostname='localhost') as secured:
            return hashlib.sha256(secured.getpeercert(binary_form=True)).hexdigest()


def verify(stack):
    client = Client(stack)
    port = int(stack.release['compose_environment']['PARKING_HTTPS_PORT'])
    # No verify=False, curl -k or browser certificate bypass in these TLS checks.
    for context, hostname in [(ssl.create_default_context(), 'localhost'), (client.context, 'wrong-host')]:
        try:
            with socket.create_connection(('127.0.0.1', port), timeout=5) as raw:
                context.wrap_socket(raw, server_hostname=hostname)
        except ssl.SSLCertVerificationError:
            pass
        else:
            raise AssertionError('Invalid trust/hostname unexpectedly accepted')
    plain = http.client.HTTPConnection('127.0.0.1', int(stack.release['compose_environment']['PARKING_HTTPS_HTTP_PORT']))
    plain.request('GET', '/zones/zone-01?redirect_probe=1', headers={'Host': 'untrusted.example'})
    response = plain.getresponse()
    assert response.status == 308 and response.getheader('Location') == stack.release['url'] + '/zones/zone-01?redirect_probe=1'
    plain.close()
    config = json.loads(stack.dc('config', '--format', 'json'))
    validate_network_boundary(config)
    for service, artifact in [('backend', 'backend'), ('postgres', 'postgres'), ('mosquitto', 'mqtt'), ('web', 'web')]:
        cid = stack.dc('ps', '-q', service).strip()
        actual_image = stack.run('docker', 'inspect', '--format', '{{.Image}}', cid).strip()
        assert actual_image == stack.release['images'][artifact]['image_id']
        bindings = json.loads(stack.run('docker', 'inspect', '--format', '{{json .HostConfig.PortBindings}}', cid))
        if service != 'web':
            assert not bindings, service + ' must not publish any port'
    for path in ('/actuator', '/actuator/health', '/actuator/prometheus', '/metrics', '/grafana', '/prometheus'):
        assert client.request(path)[0] == 404
    assert client.request('/')[0] == 200
    print('PASS: strict CA/SAN verification, fixed HTTP redirect, internal ports and management paths', flush=True)

    password = Path(stack.release['password_file']).read_text().strip()
    assert client.request('/api/auth/login', 'POST', {'username': 'https-viewer', 'password': password})[0] == 403
    assert client.request('/api/auth/me')[0] == 401
    client.login('https-viewer', password)
    assert client.request('/api/auth/me')[0] == 200
    connection, stream = client.stream()
    first = snapshot(stream)
    with concurrent.futures.ThreadPoolExecutor(max_workers=1) as pool:
        sending = pool.submit(stack.simulate, 8)
        deadline = time.monotonic() + 20
        while time.monotonic() < deadline:
            latest = snapshot(stream)
            if latest.get('snapshot') and latest['view_revision'] > first['view_revision']:
                break
        else:
            raise AssertionError('SSE proxy did not deliver a new committed snapshot')
        assert '"status": "STORED"' in sending.result()
    event_id = client.request('/api/zones/zone-01/status')[2]['snapshot']['current_event_id']
    assert client.request('/api/events/' + event_id)[0] == 200
    token = client.request('/api/csrf')[2]['token']
    assert client.request('/api/auth/logout', 'POST', token=token)[0] == 204
    stream.read()  # Must reach EOF within the socket deadline after logout.
    connection.close()
    assert client.request('/api/auth/me')[0] == 401
    print('PASS: real HTTPS login, CSRF, Secure/HttpOnly/SameSite cookies, commit/SSE and logout closure', flush=True)

    account = stack.account(['create-user', '--username=revoke-probe', '--role=VIEWER', '--zone-id=zone-01'])
    revoked = Client(stack)
    revoked.login('revoke-probe', (stack.state / 'accounts/revoke-probe.password').read_text())
    connection, stream = revoked.stream(); snapshot(stream)
    stack.account(['revoke-zone', '--user-id=' + account['user_id'], '--zone-id=zone-01'])
    stream.read(); connection.close()
    assert revoked.request('/api/zones/zone-01/status')[0] == 403
    stack.account(['disable-user', '--user-id=' + account['user_id']])
    assert revoked.request('/api/auth/me')[0] == 401
    print('PASS: operator-issued account, revoked zone closes SSE, disabled account invalidates session', flush=True)

    client.login('https-viewer', password)
    serial = certificate_hash(stack)
    stack.rotate_certificate()
    deadline = time.monotonic() + 10
    while certificate_hash(stack) == serial and time.monotonic() < deadline:
        time.sleep(.25)
    assert certificate_hash(stack) != serial
    assert client.request('/api/auth/me')[0] == 200
    network = stack.release['project'] + '_app'
    old_container = stack.dc('ps', '-q', 'backend').strip()
    old_networks = json.loads(stack.run('docker', 'inspect', '--format', '{{json .NetworkSettings.Networks}}', old_container))
    old_ip = old_networks[network]['IPAddress']
    stack.dc('stop', 'backend')
    # Docker DNS may retain the stopped address (504) or drop it (502).
    # Require a gateway failure; convergence to a particular status is not the contract.
    stopped_status = client.request('/api/auth/me?secret_query_probe=DO_NOT_LOG_O2')[0]
    assert stopped_status in (502, 504), 'Unexpected stopped-backend response: ' + str(stopped_status)
    stack.dc('rm', '-f', 'backend')
    holder = stack.release['project'] + '-dns-reservation'
    try:
        stack.run('docker', 'run', '-d', '--name', holder, '--network', network, '--ip', old_ip,
                  '--entrypoint', '/bin/sh', stack.release['images']['web']['image_id'], '-c', 'sleep 180')
        stack.dc('up', '-d', 'backend')
        new_container = stack.dc('ps', '-q', 'backend').strip()
        new_networks = json.loads(stack.run('docker', 'inspect', '--format', '{{json .NetworkSettings.Networks}}', new_container))
        assert new_networks[network]['IPAddress'] != old_ip, 'DNS recovery must use a different backend IP'
        stack.wait_ready()
    finally:
        stack.run('docker', 'rm', '-f', holder)
    assert client.request('/api/auth/me')[0] == 401
    client.login('https-viewer', password)
    assert client.request('/api/events/' + event_id)[0] == 200
    connection, stream = client.stream(); snapshot(stream); connection.close()
    stack.up()  # Repeat start must reuse the same DB, secrets and image identities.
    assert client.request('/api/events/' + event_id)[0] == 200
    logs = stack.dc('logs', 'web')
    assert 'DO_NOT_LOG_O2' not in logs and password not in logs
    print(f'PASS: certificate renewal/reload, API {stopped_status}, DNS reconnect, session reset, history preservation and sanitized logs', flush=True)
    return {'event_id': event_id, 'stopped_backend_status': stopped_status,
            'images': stack.release['images'], 'configuration_sha256': stack.release['configuration_sha256']}


def main():
    name = uuid.uuid4().hex[:8]
    stack = LocalHttps(ROOT / '.local-state/o2' / name)
    try:
        stack.prepare(free_port(), free_port(), 'parking-o2-' + name, 'isolated-o2-acceptance')
        evidence = verify(stack)
        print('Running real Chromium HTTPS flow...', flush=True)
        stack.run('node', 'frontend/node_modules/@playwright/test/cli.js', 'test', '--config', 'frontend/playwright.https.config.ts',
                  timeout=180, env=dict(__import__('os').environ, PARKING_HTTPS_STATE=str(stack.state)))
        print('PASS: Chromium HTTPS login, secure cookie, live MQTT/SSE, reconnect and logout', flush=True)
        print('O2_ACCEPTANCE_PASSED', flush=True)
        (ROOT / '.local-state/o2-last-result.json').write_text(json.dumps(evidence, indent=2) + '\n')
    except Exception:
        if stack.release:
            print(stack.redact(stack.dc('logs', '--tail', '20', 'web', 'backend', 'postgres', 'mosquitto')), flush=True)
        raise
    finally:
        if stack.release:
            stack.cleanup_test()


if __name__ == '__main__':
    main()
