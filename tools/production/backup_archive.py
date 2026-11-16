"""Authenticated, bounded LOCAL_ONLY demo archives. Keys are never part of the archive."""
import base64
import hashlib
import json
from pathlib import PurePosixPath
from cryptography.fernet import Fernet

MAX_BYTES = 128 * 1024 * 1024  # Deliberate local-demo limit; use streaming tooling for large production DBs.


def valid_path(name):
    path = PurePosixPath(name)
    if (not name or name == 'metadata.json' or path.is_absolute() or '..' in path.parts
            or str(path) != name or '\\' in name):
        raise ValueError('unsafe_archive_path')


def seal(files, metadata, key):
    if sum(len(value) for value in files.values()) > MAX_BYTES:
        raise ValueError('local_backup_size_limit')
    for name in files:
        valid_path(name)
    payload = {'format': 1, 'metadata': metadata, 'files': {
        name: {'sha256': hashlib.sha256(value).hexdigest(), 'data': base64.b64encode(value).decode('ascii')}
        for name, value in files.items()}}
    return Fernet(key).encrypt(json.dumps(payload, ensure_ascii=False, separators=(',', ':')).encode())


def unseal(blob, key):
    if len(blob) > MAX_BYTES * 2:
        raise ValueError('local_backup_size_limit')
    payload = json.loads(Fernet(key).decrypt(blob))  # Authenticate the whole archive before using any file.
    if payload.get('format') != 1 or not isinstance(payload.get('metadata'), dict):
        raise ValueError('unknown_backup_format')
    files = {}
    for name, entry in payload['files'].items():
        valid_path(name)
        value = base64.b64decode(entry['data'], validate=True)
        if hashlib.sha256(value).hexdigest() != entry['sha256']:
            raise ValueError('backup_member_checksum_mismatch')
        files[name] = value
    if sum(map(len, files.values())) > MAX_BYTES:
        raise ValueError('local_backup_size_limit')
    return files, payload['metadata']
