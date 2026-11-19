#!/usr/bin/env python3
"""Actual local PostgreSQL/HTTPS/MQTT/Grafana restore drill, isolated from existing learning stacks."""
import argparse
import json
import shutil
import time
import uuid
from pathlib import Path
from local_backup import LocalBackup, ROOT, digest
from verify_o2 import Client, free_port


def wait(description, check, seconds=65):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        try:
            if check():
                return
        except (RuntimeError, OSError, ValueError, KeyError):
            pass
        time.sleep(1)
    raise AssertionError('timeout:' + description)


def login(stack):
    client = Client(stack)
    client.login(stack.release['username'], Path(stack.release['password_file']).read_text())
    return client


def startup_rejected(stack, database, marker):
    name = stack.release['project'] + '-rollback-probe'
    stack.dc('run', '-d', '--no-deps', '--name', name, '-e', 'PARKING_DB_URL=jdbc:postgresql://postgres:5432/' + database, 'backend')
    try:
        wait('unsafe backend startup refused', lambda: stack.run('docker', 'inspect', '--format', '{{.State.Status}}', name).strip() == 'exited', 50)
        logs = stack.run('docker', 'logs', name)
        assert marker in logs and 'EVENT_INGEST_READY' not in logs, stack.redact(logs[-1500:])
    except Exception:
        # Preserve why startup did not fail as expected before removing this probe.
        try:
            detail = stack.redact(stack.run('docker', 'logs', name))
            (stack.state / 'rollback-probe-failure.txt').write_text(detail)
            print(detail[-6000:], flush=True)
        except Exception:
            print('ROLLBACK_PROBE_DIAGNOSTICS_UNAVAILABLE', flush=True)
        raise
    finally:
        stack.run('docker', 'rm', '-f', name)


def pending(stack):
    script = "import sqlite3,json; c=sqlite3.connect('file:/state/device.sqlite?mode=ro',uri=True); print(json.dumps(c.execute('SELECT payload FROM outbox WHERE status=\"PENDING\" ORDER BY rowid').fetchall()))"
    return json.loads(stack.dc('run', '--rm', '-T', '--no-deps', '--entrypoint', 'python', 'simulator', '-c', script))


def run(stack):
    print('O4_PHASE normal HTTPS, stored ACK and baseline', flush=True)
    client = login(stack)
    assert client.request('/api/zones/zone-01/status')[0] == 200
    output = stack.simulate(4)
    assert '"status": "STORED"' in output and pending(stack) == []
    baseline = int(stack.sql('SELECT count(*) FROM event_message'))
    previous_revision = int(stack.sql('SELECT view_revision FROM zone_view'))
    print('O4_PHASE encrypted backup and real isolated pg_restore comparison', flush=True)
    backup = stack.backup()
    archive = stack.state / 'backups' / backup['archive']
    saved, metadata = stack.load_archive(archive)
    assert saved['database.dump'].startswith(b'PGDMP') and 'simulator.sqlite' in saved
    assert int(stack.sql('SELECT view_revision FROM zone_view')) > previous_revision
    wait('verified backup metric', lambda: stack.prom('parking_backup_status_valid') == 1.0)
    assert stack.prom('parking_backup_last_verified_timestamp_seconds') > 0
    dashboard = stack.grafana('/api/dashboards/uid/parking-backup')['dashboard']
    assert len(dashboard['panels']) == 3
    print('O4_PHASE already ACKed post-backup loss and rollback detection', flush=True)
    output = stack.simulate(4)
    assert '"status": "STORED"' in output and pending(stack) == []
    newer_count = int(stack.sql('SELECT count(*) FROM event_message'))
    highest_end = int(stack.sql('SELECT revision_range_end FROM zone_view'))
    assert newer_count > baseline
    source_database = stack.database
    stack.quiesce()
    unsafe_database = 'parking_verify_' + uuid.uuid4().hex[:8]
    stack.create_database(unsafe_database, saved['database.dump'])
    latest_primary = stack.volume_file('/state/revisions.json')
    latest_copy = stack.volume_file('/state-copy/revisions.json')
    startup_rejected(stack, unsafe_database, 'manifest_db_range_mismatch')
    stack.volume_file('/state/revisions.json', saved['revision-primary.json'])
    stack.volume_file('/state-copy/revisions.json', saved['revision-copy.json'])
    startup_rejected(stack, unsafe_database, 'revision_ledger_mismatch')
    stack.volume_file('/state/revisions.json', latest_primary)
    stack.volume_file('/state-copy/revisions.json', latest_copy)
    stack.sql('DROP DATABASE ' + unsafe_database, 'parking_handoff')
    stack.up()
    print('O4_PHASE isolated restore remains closed for API, SSE and MQTT', flush=True)
    restored = stack.restore(archive)
    assert restored['result'] == 'RECOVERY_PREPARED'
    assert stack.database != source_database
    assert int(stack.sql('SELECT count(*) FROM event_message')) == baseline
    assert int(stack.sql('SELECT count(*) FROM event_message', source_database)) == newer_count
    assert int(stack.sql('SELECT view_revision FROM zone_view')) > highest_end
    client = login(stack)
    assert client.request('/api/zones/zone-01/status')[0] == 503
    assert client.request('/api/stream?zone_id=zone-01')[0] == 503
    held = stack.simulate(4)
    assert 'APPLICATION_ACK' not in held and pending(stack)
    assert int(stack.sql('SELECT count(*) FROM event_message')) == baseline
    wait('readiness held', lambda: stack.prom('parking_readiness_ready') == 0.0)
    print('O4_PHASE explicit device reconciliation, report-bound acceptance and retry', flush=True)
    device_report = stack.state / 'device-report.json'
    device_report.write_text(pending(stack)[-1][0])
    reconciled = stack.reconcile(device_report)
    assert reconciled['result'] == 'RECOVERY_RECONCILED'
    client = login(stack)
    assert client.request('/api/zones/zone-01/status')[0] == 503
    # A report and an explicit loss acknowledgement are both necessary.
    stack.quiesce()
    try:
        stack.recovery('accept', '--report-sha256=' + reconciled['report_sha256'], '--reason=negative acceptance test')
        raise AssertionError('acceptance without loss acknowledgement must fail')
    except RuntimeError as error:
        assert 'explicit_backup_data_loss_acknowledgement_required' in str(error)
    accepted = stack.accept(reconciled['report_sha256'])
    assert accepted['result'] == 'RECOVERY_ACCEPTED'
    client = login(stack)
    status, _, zone = client.request('/api/zones/zone-01/status')
    assert status == 200 and zone['view_revision'] > highest_end
    connection, response = client.stream()
    response.close(); connection.close()
    retried = stack.simulate(4)
    assert '"status": "DUPLICATE"' in retried and '"status": "STORED"' in retried and pending(stack) == []
    wait('readiness recovered', lambda: stack.prom('parking_readiness_ready') == 1.0)
    print('O4_PHASE independent store outage fails closed and requires backend restart', flush=True)
    stack.dc('stop', 'revision-ledger')
    wait('ledger outage read refusal', lambda: client.request('/api/zones/zone-01/status')[0] == 503)
    held = stack.simulate(3)
    assert 'APPLICATION_ACK' not in held and pending(stack)
    stack.dc('start', 'revision-ledger')
    time.sleep(2)
    assert client.request('/api/zones/zone-01/status')[0] == 503  # Latched closed, no automatic trust reset.
    stack.dc('restart', 'backend')
    stack.wait_ready()
    client = login(stack)
    assert client.request('/api/zones/zone-01/status')[0] == 200
    assert '"status": "STORED"' in stack.simulate(4)
    # Rebuild a fresh verified backup of the accepted state for the learning environment.
    final_backup = stack.backup()
    wait('final backup metric', lambda: stack.prom('parking_backup_status_valid') == 1.0)
    record = {'result': 'O4_ACCEPTANCE_PASSED', 'scope': 'LOCAL_ONLY', 'project': stack.release['project'],
        'images': stack.release['images'], 'configuration_sha256': stack.release['configuration_sha256'],
        'source_sha256': stack.release['source_sha256'], 'backup': backup, 'final_backup': final_backup,
        'source_database_preserved': source_database, 'acked_post_backup_rows_missing_from_restored_backup': newer_count-baseline,
        'restore': stack.release['restore'], 'new_revision_greater_than_old_reserved_end': highest_end,
        'checks': ['strict HTTPS login', 'encrypted dump and SQLite backup API', 'all-table restored content comparison',
            'DB-only rollback refused', 'DB and both local files rollback refused', 'API/SSE/ordinary MQTT held',
            'fresh validated device report without ACK', 'explicit report hash and loss acceptance', 'duplicate retry after acceptance',
            'independent store outage latches closed', 'Prometheus verified-backup metrics', 'Grafana provisioned backup dashboard'],
        'limits': ['same Docker host; no off-host durability claim', 'controlled local fixture size and timing only',
                   'no real device, external provider, cloud deployment or remote alert delivery']}
    (ROOT / '.local-state/o4-last-result.json').write_text(json.dumps(record, indent=2) + '\n')
    print('O4_ACCEPTANCE_PASSED', flush=True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--keep', action='store_true', help='Keep the separate parking-backup learning environment after success')
    args = parser.parse_args()
    suffix = uuid.uuid4().hex[:8]
    project = 'parking-backup' if args.keep else 'parking-o4-' + suffix
    state = ROOT / '.local-state' / ('backup-demo' if args.keep else 'o4-' + suffix)
    stack = LocalBackup(state)
    try:
        if args.keep:
            stack.prepare()
        else:
            stack.prepare(free_port(), free_port(), project, 'acceptance-o4', grafana_port=free_port())
        run(stack)
    finally:
        if not args.keep and stack.release and stack.release.get('purpose') == 'acceptance-o4' and stack.release['project'] == project:
            stack.dc('--profile', '*', 'down', '--volumes', '--remove-orphans', timeout=120)
            # Delete only directories/keys generated for this exact isolated fixture.
            if stack.release.get('backup'):
                shutil.rmtree(stack.release['backup']['ledger_directory'])
                Path(stack.release['backup']['key_file']).unlink()
            shutil.rmtree(state)


if __name__ == '__main__':
    main()
