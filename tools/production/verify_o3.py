#!/usr/bin/env python3
"""Real O3 metrics/fault/alert acceptance in an owned disposable localhost stack."""
import json
import os
import time
import uuid
from pathlib import Path
from local_https import ROOT, validate_network_boundary
from local_monitoring import LocalMonitoring, validate_monitoring
from verify_o2 import Client, free_port, snapshot


def wait(check, label, seconds=90):
    deadline = time.monotonic() + seconds
    last = None
    while time.monotonic() < deadline:
        try:
            last = check()
            if last: return
        except (OSError, RuntimeError, KeyError, ValueError):
            pass
        time.sleep(1)
    raise AssertionError('Timed out: ' + label + '; last=' + str(last))


def alert(stack, name, firing=True):
    rows = stack.prometheus_api('/api/v1/alerts')['data']['alerts']
    found = any(row['labels']['alertname'] == name and row['state'] == 'firing' for row in rows)
    return found if firing else not found


def wait_positive_metric(stack, expression, seconds=90):
    def sampled_positive():
        value = stack.prom(expression)
        return value is not None and value > 0
    wait(sampled_positive, 'positive metric: ' + expression, seconds=seconds)


def outbox(stack):
    return int(stack.dc('run', '--rm', '--no-deps', '--entrypoint', 'python3', 'simulator', '-c',
                       "import sqlite3; print(sqlite3.connect('/state/device.sqlite').execute(\"SELECT count(*) FROM outbox WHERE status='PENDING'\").fetchone()[0])").strip())


def current_counter(stack, name, labels=''):
    # Baselines must be current, not the last Prometheus scrape from before a replay.
    prefix=name+labels+' '
    line=next(line for line in stack.management()['body'].splitlines() if line.startswith(prefix))
    return float(line[len(prefix):].split()[0])


def verify(stack):
    assert stack.release['purpose'] == 'isolated-o3-acceptance', 'Faults require a disposable O3 project'
    config = json.loads(stack.dc('--profile', '*', 'config', '--format', 'json'))
    validate_network_boundary(config, ('web', 'grafana'))
    validate_monitoring(config, stack.release['images'])
    for service, artifact in [('backend','backend'),('web','web'),('postgres','postgres'),('mosquitto','mqtt'),
                              ('prometheus','prometheus'),('grafana','grafana'),('node-exporter','node'),('blackbox','blackbox')]:
        container = stack.dc('ps', '-q', service).strip()
        actual = stack.run('docker', 'inspect', '--format', '{{.Image}}', container).strip()
        assert actual == stack.release['images'][artifact]['image_id'], service
        bindings = json.loads(stack.run('docker', 'inspect', '--format', '{{json .HostConfig.PortBindings}}', container))
        if service not in ('web','grafana'): assert not bindings, service
        else: assert all(p['HostIp'] == '127.0.0.1' for ports in bindings.values() for p in ports)
    assert stack.management(authenticated=False)['status'] == 401
    assert stack.management()['status'] == 200
    assert stack.management('/actuator/env')['status'] == 403
    assert stack.management(port=8080)['status'] != 200
    client = Client(stack)
    for path in ('/actuator/prometheus','/actuator/health/readiness','/grafana','/prometheus'):
        assert client.request(path)[0] == 404
    # min(up)=1 does not prove that targets with no first scrape are present yet.
    wait(lambda: stack.prom('count(up)') == 4 and stack.prom('min(up)') == 1, 'all four scrape targets')
    wait(lambda: stack.prom('parking_readiness_ready') == 1, 'read readiness')
    wait(lambda: stack.prom('min(parking_mqtt_connected)') == 1, 'all MQTT channels')
    wait(lambda: stack.prom('probe_success{job="https-internal"}') == 1, 'strict internal HTTPS probe')
    assert stack.prom('probe_ssl_earliest_cert_expiry{job="https-internal"} - time()') > 0
    for expression in ('node_memory_MemTotal_bytes', 'sum(node_cpu_seconds_total)',
                       'node_filesystem_size_bytes{mountpoint="/"}', 'node_filesystem_files{mountpoint="/"}',
                       'sum(jvm_memory_used_bytes)', 'hikaricp_connections_max'):
        wait_positive_metric(stack, expression)
    # Both series must exist; `active or pending` would silently discard pending.
    assert stack.prom('count({__name__=~"hikaricp_connections_(active|pending)"})') == 2
    print('PASS: authenticated private management, bound images/ports, actual JVM/DB pool/Linux filesystem/HTTPS metrics', flush=True)

    client.login('https-viewer', Path(stack.release['password_file']).read_text().strip())
    connections = []
    try:
        for _ in range(3):
            connection, response = client.stream(); snapshot(response)
            connections.append((connection, response))
        wait(lambda: stack.prom('parking_sse_active') == 3, 'three real SSE connections')
        assert client.request('/api/stream?zone_id=zone-01')[0] == 429
        wait(lambda: stack.prom('parking_sse_rejected_total') >= 1, 'SSE rejection counter')
    finally:
        for connection, response in connections: response.close(); connection.close()
    wait(lambda: stack.prom('parking_sse_active') == 0, 'SSE cleanup')
    output = stack.simulate()
    assert '"status": "STORED"' in output
    wait(lambda: stack.prom('parking_ingest_total{outcome="stored"}') >= 1, 'committed event counter')
    wait(lambda: stack.prom('parking_ack_publish_total{result="success"}') >= 1, 'broker accepted ACK')
    assert stack.prom('parking_ingest_commit_seconds_count') >= 1
    original_rows = int(stack.sql('SELECT count(*) FROM event_message'))
    event_id = client.request('/api/zones/zone-01/status')[2]['snapshot']['current_event_id']
    print('PASS: real SSE capacity/cleanup, MQTT event commit, application ACK and timer', flush=True)

    failure_before = current_counter(stack, 'parking_commit_failures_total')
    ack_before = current_counter(stack, 'parking_ack_publish_total', '{result="success"}')
    stack.sql('REVOKE INSERT ON event_message FROM parking_runtime')
    try:
        output = stack.dc('run', '--rm', 'simulator', timeout=50)
        assert 'APPLICATION_ACK' not in output
        assert int(stack.sql('SELECT count(*) FROM event_message')) == original_rows
        assert outbox(stack) >= 1
        wait(lambda: stack.prom('parking_commit_failures_total') > failure_before, 'real DB write failure')
        assert stack.prom('parking_ack_publish_total{result="success"}') == ack_before
        wait(lambda: alert(stack, 'ParkingCommitFailures'), 'database failure alert')
    finally:
        stack.sql('GRANT INSERT ON event_message TO parking_runtime')
    assert '"status": "STORED"' in stack.simulate()
    assert outbox(stack) == 0
    print('PASS: denied DB write increments failure, no ACK, outbox retained; restored GRANT commits retry', flush=True)

    wait(lambda: stack.prom('parking_ack_publish_total{result="success"}') > ack_before, 'post-recovery ACK sample')
    failed_ack_before = current_counter(stack, 'parking_ack_publish_total', '{result="failure"}')
    ack_before = current_counter(stack, 'parking_ack_publish_total', '{result="success"}')
    stored_before = int(stack.sql('SELECT count(*) FROM event_message'))
    acl = stack.state / 'broker/acl'; original_acl = acl.read_text()
    try:
        acl.write_text(original_acl.replace('topic write parking/v2/devices/+/acks\n', ''))
        stack.dc('kill', '-s', 'HUP', 'mosquitto')
        output = stack.dc('run', '--rm', 'simulator', timeout=50)
        assert 'APPLICATION_ACK' not in output
        assert int(stack.sql('SELECT count(*) FROM event_message')) > stored_before
        assert outbox(stack) >= 1
        wait(lambda: stack.prom('parking_ack_publish_total{result="failure"}') > failed_ack_before, 'negative MQTT5 PUBACK failure')
        assert stack.prom('parking_ack_publish_total{result="success"}') == ack_before
        wait(lambda: alert(stack, 'ParkingAckFailures'), 'ACK rejection alert')
    finally:
        acl.write_text(original_acl)
        stack.dc('kill', '-s', 'HUP', 'mosquitto')
    output = stack.simulate()
    assert '"status": "DUPLICATE"' in output and outbox(stack) == 0
    wait(lambda: stack.prom('parking_ingest_total{outcome="duplicate"}') >= 1, 'receipt replay')
    print('PASS: actual ACL negative PUBACK is failure after commit; no false success, duplicate replay recovers ACK', flush=True)

    stack.dc('stop', 'mosquitto')
    try:
        wait(lambda: stack.prom('max(parking_mqtt_connected)') == 0, 'all disconnected MQTT callbacks')
        assert stack.prom('parking_readiness_ready') == 1
        assert client.request('/api/events/' + event_id)[0] == 200
        assert stack.management('/actuator/health/readiness')['status'] == 200
        wait(lambda: alert(stack, 'ParkingMqttDisconnected'), '30-second MQTT alert')
    finally:
        stack.dc('start', 'mosquitto')
    wait(lambda: stack.prom('min(parking_mqtt_connected)') == 1, 'MQTT reconnect')
    wait(lambda: alert(stack, 'ParkingMqttDisconnected', False), 'MQTT alert resolution')
    print('PASS: broker outage does not block stored reads/readiness; MQTT alert fires and clears after reconnect', flush=True)

    stack.dc('stop', 'postgres')
    try:
        wait(lambda: stack.prom('parking_database_available') == 0, 'DB probe unavailable')
        assert stack.prom('parking_readiness_ready') == 0
        assert stack.management('/actuator/health/liveness')['status'] == 200
        assert stack.management('/actuator/health/readiness')['status'] == 503
        assert client.request('/api/zones/zone-01/status')[0] == 503
        wait(lambda: alert(stack, 'ParkingReadNotReady'), 'readiness alert')
    finally:
        stack.dc('start', 'postgres')
    wait(lambda: stack.prom('parking_readiness_ready') == 1, 'DB recovery without restart')
    assert client.request('/api/events/' + event_id)[0] == 200
    stack.sql("UPDATE zone_view SET recovery_hold=true WHERE zone_id='zone-01'")
    try:
        wait(lambda: stack.prom('parking_revision_ready') == 0, 'recovery hold')
        assert stack.prom('parking_database_available') == 1
        assert client.request('/api/zones/zone-01/status')[0] == 503
    finally:
        stack.sql("UPDATE zone_view SET recovery_hold=false WHERE zone_id='zone-01'")
    wait(lambda: stack.prom('parking_readiness_ready') == 1, 'explicit test hold removal')
    print('PASS: DB outage keeps liveness UP but readiness/API unavailable; DB recovery and revision hold remain distinct', flush=True)

    # A valid topic but uncorrelated body must never become a free-form metric label or server log.
    sentinel = 'O3_PRIVATE_SENTINEL_' + uuid.uuid4().hex
    publish = '''import sys,time
sys.path.insert(0,'/source/simulator')
import device
c=device.mqtt.Client(device.mqtt.CallbackAPIVersion.VERSION2,protocol=device.mqtt.MQTTv5)
device.configure_transport(c,'/tls/ca.crt','edge-01','/run/secrets/edge-password')
c.connect('mosquitto',8883,keepalive=15);c.loop_start()
token=c.publish('parking/v2/devices/edge-01/events',PAYLOAD,qos=1)
token.wait_for_publish(timeout=5);time.sleep(1);c.disconnect();c.loop_stop()
'''.replace('PAYLOAD', repr(json.dumps({'message_id':sentinel,'private_body':sentinel})))
    stack.dc('run', '--rm', '--no-deps', '-T', '--entrypoint', 'python3', 'simulator', '-', input=publish)
    client.request('/api/auth/me?private_probe=' + sentinel)
    metrics = stack.management()['body']
    logs = stack.dc('logs', 'backend', 'web', 'prometheus', 'grafana')
    assert sentinel not in metrics and sentinel not in logs
    for directory in ('secrets','accounts'):
        for file in (stack.state / directory).glob('*'):
            if file.is_file():
                value = file.read_text().strip()
                assert value not in metrics and value not in logs, 'Secret leaked from ' + file.name
    print('PASS: untrusted body/query and file credentials absent from operational metrics and application/proxy logs', flush=True)

    historical_query = 'parking_ingest_total{outcome="stored"} @ ' + str(int(time.time()))
    historical_value = stack.prom(historical_query)
    assert historical_value >= 1
    stack.dc('stop', 'backend')
    try:
        wait(lambda: stack.prom('up{job="parking"}') == 0, 'backend scrape down')
        wait(lambda: alert(stack, 'ParkingBackendDown'), 'one-minute backend alert', seconds=110)
        wait(lambda: alert(stack, 'ParkingHttpsInternalDown'), 'internal HTTPS failure alert')
    finally:
        stack.dc('start', 'backend')
    stack.wait_ready()
    wait(lambda: stack.prom('up{job="parking"}') == 1 and stack.prom('parking_readiness_ready') == 1, 'backend restoration')
    wait(lambda: alert(stack, 'ParkingBackendDown', False), 'backend alert resolution')
    wait(lambda: stack.prom('probe_success{job="https-internal"}') == 1 and
         alert(stack, 'ParkingHttpsInternalDown', False), 'HTTPS probe and alert recovery')
    client.login('https-viewer', Path(stack.release['password_file']).read_text().strip())
    assert client.request('/api/events/' + event_id)[0] == 200
    stack.dc('restart', 'prometheus', 'grafana')
    stack.up()
    assert stack.prom(historical_query) == historical_value
    wait(lambda: alert(stack, 'ParkingCertificateExpiring'), 'certificate rule after monitoring restart')
    print('PASS: actual backend down/HTTPS alerts, restored readiness and persisted application/monitoring data', flush=True)
    return {'images':stack.release['images'],'configuration_sha256':stack.release['configuration_sha256'],
            'event_id':event_id,'rule_scenarios':19,'source_commit':stack.release['source_commit'],
            'source_sha256':stack.release['source_sha256']}


def main():
    name = uuid.uuid4().hex[:8]
    stack = LocalMonitoring(ROOT / '.local-state/o3' / name)
    try:
        stack.prepare(free_port(), free_port(), 'parking-o3-' + name, 'isolated-o3-acceptance', free_port())
        evidence = verify(stack)
        stack.run('node', 'frontend/node_modules/@playwright/test/cli.js', 'test', '--config', 'frontend/playwright.monitoring.config.ts',
                  timeout=180, env=dict(os.environ, PARKING_MONITORING_STATE=str(stack.state)))
        print('PASS: real Grafana browser login, provisioned dashboard, live datasource responses and alert panel', flush=True)
        (ROOT / '.local-state/o3-last-result.json').write_text(json.dumps(evidence,indent=2)+'\n')
        print('O3_ACCEPTANCE_PASSED', flush=True)
    except Exception:
        if stack.release:
            print(stack.redact(stack.dc('logs','--tail','15','backend','web','prometheus','grafana','node-exporter','blackbox')),flush=True)
        raise
    finally:
        if stack.release: stack.cleanup_test()


if __name__ == '__main__': main()
