#!/usr/bin/env python3
"""O4 local backup/recovery lab. Never overwrites the source database or an unrelated project."""
import argparse
import base64
import datetime as dt
import hashlib
import json
import os
import re
import secrets
import shutil
import subprocess
import time
import uuid
from pathlib import Path
from cryptography.fernet import Fernet
from backup_archive import seal, unseal
from local_https import LocalHttps, ROOT, digest
from local_monitoring import LocalMonitoring

DEFAULT_STATE = ROOT / '.local-state/backup-demo'
CONFIGS = ['infra/backup/compose.yaml', 'infra/backup/ledger.py', 'infra/backup/ledger.Dockerfile',
           'infra/backup/prometheus.yml', 'infra/backup/alerts.yml', 'infra/backup/rules.test.yml', 'infra/backup/grafana.json']


class LocalBackup(LocalMonitoring):
    compose_files = (*LocalMonitoring.compose_files, 'infra/backup/compose.yaml')

    def __init__(self, state=DEFAULT_STATE):
        super().__init__(state)

    def prepare(self, https_port=20443, http_port=20480, project='parking-backup', purpose='learning-backup', grafana_port=23000):
        super().prepare(https_port, http_port, project, purpose, grafana_port=grafana_port)

    def configure_release(self):
        super().configure_release()
        dashboards = self.state / 'backup-dashboards'
        dashboards.mkdir()
        shutil.copy(ROOT / 'infra/monitoring/grafana/dashboards/parking.json', dashboards / 'parking.json')
        shutil.copy(ROOT / 'infra/backup/grafana.json', dashboards / 'backup.json')
        identifier = str(uuid.uuid4())
        ledger_dir = ROOT / '.local-state/revision-ledgers' / identifier
        ledger_dir.mkdir(parents=True, mode=0o700)
        keys = ROOT / '.local-state/backup-keys'
        keys.mkdir(parents=True, exist_ok=True, mode=0o700)
        key_file = keys / (identifier + '.key')
        with key_file.open('xb') as file:
            file.write(Fernet.generate_key())
        key_file.chmod(0o600)
        (self.state / 'recovery').mkdir(mode=0o777)
        # Host parent is private. The recovery container runs as UID10001.
        (self.state / 'recovery').chmod(0o777)
        (self.state / 'backups').mkdir(mode=0o700)
        (self.state / 'backup-status').mkdir()
        self.write_secret('revision-ledger-token', secrets.token_urlsafe(36))
        tag = 'parking-revision-ledger:' + self.release['project']
        self.run('docker', 'build', '-f', 'infra/backup/ledger.Dockerfile', '-t', tag, '.', timeout=300)
        image = self.run('docker', 'image', 'inspect', '--format', '{{.Id}}', tag).strip()
        self.release['images']['ledger'] = {'source': 'infra/backup/ledger.Dockerfile', 'image_id': image}
        self.release['configuration_sha256'].update({p: digest(ROOT / p) for p in CONFIGS})
        self.release['compose_environment'].update({'PARKING_LEDGER_ID': identifier, 'PARKING_LEDGER_DIRECTORY': str(ledger_dir),
            'PARKING_LEDGER_USER': f'{os.getuid()}:{os.getgid()}',
            'PARKING_LEDGER_IMAGE': image, 'PARKING_RECOVERY_DATABASE': 'parking_handoff'})
        self.release['backup'] = {'key_file': str(key_file), 'key_ref': identifier, 'ledger_directory': str(ledger_dir)}
        self.save_release()
        self.dc('run', '--rm', '--no-deps', 'revision-ledger', 'init', '--db', '/ledger/revisions.sqlite', '--store-id', identifier)
        self.dc('up', '-d', 'revision-ledger')

    def check_release(self):
        super().check_release()
        if not self.release.get('backup') or not re.fullmatch(r'parking-(backup|o4-[a-f0-9]{8})', self.release['project']):
            raise ValueError('o4_local_release_required')
        config = json.loads(self.dc('--profile', '*', 'config', '--format', 'json'))
        if config['networks']['ledger'].get('internal') is not True:
            raise ValueError('internal_ledger_network_required')
        for service in ('backend', 'initialize-local', 'recovery'):
            if str(config['services'][service]['environment'].get('PARKING_REVISION_LEDGER_REQUIRED')).lower() != 'true':
                raise ValueError('mandatory_independent_ledger_required')
        if config['services']['revision-ledger']['image'] != self.release['images']['ledger']['image_id']:
            raise ValueError('ledger_image_binding_mismatch')
        owner = Path(self.release['backup']['ledger_directory']).stat()
        if config['services']['revision-ledger'].get('user') != f'{owner.st_uid}:{owner.st_gid}':
            raise ValueError('ledger_directory_owner_mismatch')
        for source, target in [('infra/monitoring/grafana/dashboards/parking.json', 'parking.json'), ('infra/backup/grafana.json', 'backup.json')]:
            if digest(ROOT / source) != digest(self.state / 'backup-dashboards' / target):
                raise ValueError('backup_dashboard_configuration_mismatch')

    def up(self):
        self.check_release()
        self.dc('up', '-d', 'revision-ledger')
        super().up()

    def check_rules(self):
        super().check_rules()
        self.dc('run', '--rm', '--no-deps', '--entrypoint', '/bin/promtool', 'prometheus',
                'check', 'config', '/etc/backup/prometheus.yml')
        self.dc('run', '--rm', '--no-deps', '--entrypoint', '/bin/promtool', 'prometheus',
                'test', 'rules', '/etc/backup/rules.test.yml')

    @property
    def database(self):
        return self.release['compose_environment']['PARKING_RECOVERY_DATABASE']

    def sql(self, sql, database=None, role='postgres'):
        database = database or self.database
        if not re.fullmatch(r'parking_(handoff|restore_[a-f0-9]{8}|verify_[a-f0-9]{8})', database):
            raise ValueError('owned_database_required')
        return self.dc('exec', '-T', 'postgres', 'psql', '-X', '-v', 'ON_ERROR_STOP=1', '-U', role,
                       '-d', database, '-At', '-c', sql).strip()

    def binary_pg(self, command, data=None):
        cid = self.dc('ps', '-q', 'postgres').strip()
        result = subprocess.run(['docker', 'exec', '-i', cid, *command], input=data, capture_output=True, timeout=180)
        if result.returncode:
            raise RuntimeError('postgres_backup_operation_failed:' + self.redact(result.stderr.decode(errors='replace')[-2000:]))
        return result.stdout

    def fingerprints(self, database=None):
        tables = self.sql("SELECT tablename FROM pg_tables WHERE schemaname='public' ORDER BY tablename", database).splitlines()
        result = {}
        for table in tables:
            if not re.fullmatch('[a-z_]+', table):
                raise ValueError('unexpected_table_name')
            result[table] = self.sql("SELECT count(*)||':'||md5(coalesce(string_agg(row_to_json(t)::text,E'\\n' ORDER BY row_to_json(t)::text),'')) FROM " + table + ' t', database)
        return result

    def quiesce(self):
        self.check_release()
        self.dc('--profile', 'simulation', 'stop', 'web', 'backend', 'simulator')

    def recovery(self, operation, *arguments):
        result = self.dc('run', '--rm', '-T', '--no-deps', 'recovery', operation, *arguments, timeout=120)
        return next(json.loads(line) for line in reversed(result.splitlines()) if line.startswith('{"result":'))

    def volume_file(self, path, value=None):
        if path not in ('/state/revisions.json', '/state-copy/revisions.json'):
            raise ValueError('revision_path_required')
        if value is None:
            return self.dc('run', '--rm', '-T', '--no-deps', '--entrypoint', 'cat', 'recovery', path).encode()
        self.dc('run', '--rm', '-T', '--no-deps', '--entrypoint', '/bin/sh', 'recovery', '-c', 'cat > "$1"', 'sh', path,
                input=value.decode())

    def create_database(self, name, dump):
        if not re.fullmatch(r'parking_(restore|verify)_[a-f0-9]{8}', name):
            raise ValueError('new_isolated_database_required')
        # CREATE DATABASE refuses existing names; no --clean and no drop of source DB.
        self.sql('CREATE DATABASE ' + name + ' OWNER parking_migrator', 'parking_handoff')
        self.binary_pg(['pg_restore', '--exit-on-error', '--single-transaction', '--no-owner', '--no-privileges',
                        '--role=parking_migrator', '-U', 'postgres', '-d', name], dump)
        grants = (ROOT / 'infra/deploy/sql/grants.sql').read_bytes()
        self.binary_pg(['psql', '-X', '-v', 'ON_ERROR_STOP=1', '-U', 'parking_migrator', '-d', name], grants)

    def key(self):
        path = Path(self.release['backup']['key_file'])
        if path.stat().st_mode & 0o077:
            raise ValueError('backup_key_requires_owner_only_permissions')
        return path.read_bytes()

    def backup(self):
        self.quiesce()
        started = time.time()
        files = {}
        files['database.dump'] = self.binary_pg(['/bin/sh', '-c',
            'export PGPASSWORD="$(cat /run/secrets/backup-password)"; exec pg_dump -h 127.0.0.1 -U parking_backup -Fc --no-owner --no-acl "$1"', 'sh', self.database])
        expected = self.fingerprints()
        for name, path in [('revision-primary.json', '/state/revisions.json'), ('revision-copy.json', '/state-copy/revisions.json')]:
            files[name] = self.volume_file(path)
        if json.loads(files['revision-primary.json']) != json.loads(files['revision-copy.json']):
            raise ValueError('backup_revision_pair_mismatch')
        # SQLite backup API also incorporates WAL; copying device.sqlite alone would not.
        script = "import sqlite3,base64,pathlib; p=pathlib.Path('/state/device.sqlite'); " \
                 "s=sqlite3.connect('file:'+str(p)+'?mode=ro',uri=True) if p.exists() else None; " \
                 "d=sqlite3.connect('/tmp/copy.sqlite') if s else None; s.backup(d) if s else None; " \
                 "d.close() if d else None; print(base64.b64encode(pathlib.Path('/tmp/copy.sqlite').read_bytes()).decode() if s else '')"
        encoded = self.dc('run', '--rm', '-T', '--no-deps', '--entrypoint', 'python', 'simulator', '-c', script).strip()
        if encoded:
            files['simulator.sqlite'] = base64.b64decode(encoded, validate=True)
        files['release.json'] = self.release_path.read_bytes()
        for folder in ('secrets', 'accounts', 'pki', 'broker', 'web-tls'):
            for file in (self.state / folder).iterdir():
                if file.is_file():
                    files['configuration/' + folder + '/' + file.name] = file.read_bytes()
        for name in ('create-roles.sql', 'grants.sql'):
            files['roles/' + name] = (ROOT / 'infra/deploy/sql' / name).read_bytes()
        metadata = {'scope': 'LOCAL_ONLY', 'backup_started_at': started, 'source_database': self.database,
                    'fingerprints': expected, 'key_ref': self.release['backup']['key_ref'],
                    'ledger_id': self.release['compose_environment']['PARKING_LEDGER_ID'],
                    'images': self.release['images'], 'schema': self.sql('SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank')}
        blob = seal(files, metadata, self.key())
        verified_files, verified_metadata = unseal(blob, self.key())
        name = 'parking_verify_' + uuid.uuid4().hex[:8]
        self.create_database(name, verified_files['database.dump'])
        try:
            if self.fingerprints(name) != verified_metadata['fingerprints']:
                raise ValueError('restored_database_content_mismatch')
        finally:
            self.sql('DROP DATABASE ' + name, 'parking_handoff')
        # A completed archive always has a newer independent reservation than its saved DB/files.
        fence = self.recovery('fence', '--reason=verified local backup boundary')
        identifier = dt.datetime.now(dt.timezone.utc).strftime('%Y%m%dT%H%M%SZ') + '-' + uuid.uuid4().hex[:8]
        destination = self.state / 'backups' / (identifier + '.fernet')
        with destination.open('xb') as file:
            file.write(blob); file.flush(); os.fsync(file.fileno())
        destination.chmod(0o600)
        record = {'result': 'BACKUP_VERIFIED_LOCAL', 'archive': destination.name, 'ciphertext_sha256': digest(destination),
                  'key_ref': metadata['key_ref'], 'started_at': started, 'verified_at': time.time(),
                  'restore_comparison': 'all public table row counts and sorted-row hashes', 'fence': fence}
        destination.with_suffix('.json').write_text(json.dumps(record, indent=2) + '\n')
        status = self.state / 'backup-status/status.json'
        temporary = status.with_suffix('.tmp')
        temporary.write_text(json.dumps(record, indent=2) + '\n')
        os.replace(temporary, status)
        self.up()
        return record

    def load_archive(self, archive):
        archive = Path(archive).resolve()
        if archive.parent != (self.state / 'backups').resolve() or archive.suffix != '.fernet':
            raise ValueError('select_archive_from_this_environment')
        record = json.loads(archive.with_suffix('.json').read_text())
        if digest(archive) != record['ciphertext_sha256']:
            raise ValueError('encrypted_archive_checksum_mismatch')
        files, metadata = unseal(archive.read_bytes(), self.key())
        if metadata['ledger_id'] != self.release['compose_environment']['PARKING_LEDGER_ID']:
            raise ValueError('backup_independent_store_identity_mismatch')
        return files, metadata

    def restore(self, archive):
        files, metadata = self.load_archive(archive)  # Verify before stopping a working service.
        self.quiesce()
        started = time.time()
        name = 'parking_restore_' + uuid.uuid4().hex[:8]
        self.create_database(name, files['database.dump'])
        if self.fingerprints(name) != metadata['fingerprints']:
            raise ValueError('restored_database_content_mismatch')
        # Close the restored DB before it can become the runtime target.
        self.sql('UPDATE zone_view SET recovery_hold=true', name)
        for entry, path in [('revision-primary.json', '/state/revisions.json'), ('revision-copy.json', '/state-copy/revisions.json')]:
            self.volume_file(path, files[entry])
        self.release['compose_environment']['PARKING_RECOVERY_DATABASE'] = name
        self.release['restore'] = {'source_database_preserved': metadata['source_database'], 'target_database': name,
                                   'archive': Path(archive).name, 'started_at': started,
                                   'backup_started_at': metadata['backup_started_at'], 'status': 'CLOSED'}
        self.save_release()
        result = self.recovery('prepare', '--reason=isolated local restore drill')
        self.release['restore']['status'] = 'PREPARED_HOLD'
        self.save_release()
        self.up()  # Login can work; protected data and ordinary ingestion remain blocked.
        return result

    def reconcile(self, report_file):
        self.quiesce()
        raw = Path(report_file).read_bytes()
        if len(raw) > 1_048_576:
            raise ValueError('device_report_too_large')
        (self.state / 'recovery/device-report.json').write_bytes(raw)
        result = self.recovery('reconcile')
        self.up()
        return result

    def capture(self):
        self.check_release()
        script = "import json; from simulator.device import State,event; s=State('/state/device.sqlite'); " \
                 "m=event(s,'guided'); s.queue(m); s.close(); print(json.dumps(m))"
        payload = self.dc('run', '--rm', '-T', '--no-deps', '--entrypoint', 'python', 'simulator', '-c', script)
        destination = self.state / 'device-report.json'
        destination.write_text(payload)
        destination.chmod(0o600)
        return {'result': 'CAPTURED_VIRTUAL_REPORT', 'file': str(destination.relative_to(ROOT)), 'real_device': False}

    def accept(self, report_hash):
        if not re.fullmatch('[a-f0-9]{64}', report_hash):
            raise ValueError('explicit_report_hash_required')
        self.quiesce()
        result = self.recovery('accept', '--report-sha256=' + report_hash, '--acknowledge-loss=true',
                               '--reason=explicit local restore report acceptance')
        self.up()
        if self.release.get('restore'):
            restored = self.release['restore']
            restored.update({'status': 'ACCEPTED_LOCAL', 'accepted_at': time.time(),
                'rto_seconds': time.time() - restored['started_at'],
                'backup_age_at_outage_seconds': restored['started_at'] - restored['backup_started_at']})
            self.save_release()
        return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--state', type=Path, default=DEFAULT_STATE)
    parser.add_argument('command', choices=['prepare', 'up', 'stop', 'status', 'simulate', 'backup', 'restore', 'capture', 'reconcile', 'accept'])
    parser.add_argument('value', nargs='?')
    args = parser.parse_args()
    stack = LocalBackup(args.state)
    if args.command in ('restore', 'reconcile', 'accept') and not args.value:
        parser.error('this operation requires its archive, device-report path, or report SHA-256')
    if args.command == 'prepare': stack.prepare()
    elif args.command == 'up': stack.up()
    elif args.command == 'stop': stack.dc('--profile', '*', 'stop')
    elif args.command == 'status': print(stack.dc('ps')); return
    elif args.command == 'simulate': print(stack.simulate()); return
    elif args.command == 'backup': print(json.dumps(stack.backup(), indent=2)); return
    elif args.command == 'capture': print(json.dumps(stack.capture(), indent=2)); return
    else: print(json.dumps(getattr(stack, args.command)(args.value), indent=2)); return
    print('LOCAL_BACKUP_ENVIRONMENT ' + stack.release['url'])


if __name__ == '__main__':
    main()
