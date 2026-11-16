#!/usr/bin/env python3
"""Disposable Linux volume experiment. Requires an already built local ledger image."""
import argparse
import datetime
import json
import subprocess
import uuid


def docker(*args, check=True):
    return subprocess.run(['docker', *args], text=True, capture_output=True,
                          check=check, timeout=90)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--image', required=True)
    args = parser.parse_args()
    image = docker('image', 'inspect', '--format', '{{.Id}}', args.image).stdout.strip()
    volume = 'parking-ledger-permission-probe-' + uuid.uuid4().hex[:8]
    identifier = str(uuid.uuid4())
    docker('volume', 'create', volume)
    try:
        docker('run', '--rm', '--network', 'none', '--mount', f'type=volume,src={volume},dst=/ledger',
               '--entrypoint', '/bin/sh', image, '-c', 'chown 1001:1001 /ledger && chmod 0700 /ledger')
        common = ['run', '--rm', '--network', 'none', '--read-only', '--cap-drop', 'ALL',
                  '--security-opt', 'no-new-privileges:true', '--tmpfs', '/tmp',
                  '--mount', f'type=volume,src={volume},dst=/ledger']
        operation = [image, 'init', '--db', '/ledger/revisions.sqlite', '--store-id', identifier]
        before = docker(*common, *operation, check=False)
        after = docker(*common, '--user', '1001:1001', *operation, check=False)
        assert before.returncode != 0 and 'ledger_corrupt_or_unavailable' in before.stderr + before.stdout
        assert after.returncode == 0 and 'LEDGER_INITIALIZED' in after.stdout
        result = {
            'recorded_at_utc': datetime.datetime.now(datetime.timezone.utc).isoformat(),
            'image_id': image,
            'docker_server': json.loads(docker('version', '--format', '{{json .Server}}').stdout),
            'volume_owner': '1001:1001', 'volume_mode': '0700',
            'common_controls': ['network none', 'read_only', 'cap_drop ALL', 'no_new_privileges'],
            'before': {'user': 'image default root', 'exit_code': before.returncode,
                       'stdout': before.stdout.strip(), 'stderr': before.stderr.strip()},
            'after': {'user': '1001:1001', 'exit_code': after.returncode,
                      'stdout': after.stdout.strip(), 'stderr': after.stderr.strip()},
            'scope': 'Permission hypothesis only; not complete O4 acceptance',
        }
    finally:
        docker('volume', 'rm', volume)
    result['temporary_volume_removed'] = True
    print(json.dumps(result, indent=2))


if __name__ == '__main__':
    main()
