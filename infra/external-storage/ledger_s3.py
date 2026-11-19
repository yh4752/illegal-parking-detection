"""S3 versioned-head adapter. No local fallback, delete or implicit initialization.

Only this trusted gateway holds write credentials; application clients cannot PUT
arbitrary S3 objects. Version history is retained by S3 Versioning, not copied
from a DB archive. Administrative rollback remains an external trust boundary.
"""
import argparse
import base64
import hashlib
import sys
import threading
import uuid
from pathlib import Path
from botocore.exceptions import BotoCoreError, ClientError

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'backup'))
from ledger import (LedgerError, Conflict, InvalidDocument, EMPTY_DOCUMENT, MAX_BODY,
                    MAX_INTEGER, ZERO_HASH, HASH_PATTERN, canonical, digest,
                    parse_json, safe_integer, validate_document, validate_transition,
                    chain_digest, LedgerServer, Handler)


class S3Ledger:
    def __init__(self, client, bucket, key, owner, store_id):
        if str(uuid.UUID(store_id)) != store_id:
            raise LedgerError('invalid_store_identity')
        self.client, self.bucket, self.key, self.owner, self.store_id = client, bucket, key, owner, store_id
        self._lock = threading.RLock()
        self._observed = (-1, None)

    @property
    def target(self):
        return {'Bucket': self.bucket, 'Key': self.key, 'ExpectedBucketOwner': self.owner}

    def _versioning(self):
        response = self.client.get_bucket_versioning(Bucket=self.bucket, ExpectedBucketOwner=self.owner)
        if response.get('Status') != 'Enabled':
            raise LedgerError('s3_versioning_required')

    def _read(self):
        self._versioning()
        response = self.client.get_object(**self.target)  # Never request an older VersionId.
        with response['Body'] as body:
            raw = body.read(MAX_BODY + 4097)
        if len(raw) > MAX_BODY + 4096 or not response.get('ETag') or response.get('VersionId') in (None, 'null'):
            raise LedgerError('s3_head_invalid')
        head = parse_json(raw)
        if (set(head) != {'store_id', 'version', 'sha256', 'document', 'previous_chain_sha256', 'chain_sha256', 'storage', 'previous_document_sha256'}
                or head['store_id'] != self.store_id or head['storage'] != 'AWS_S3'
                or not safe_integer(head['version'])):
            raise LedgerError('s3_identity_or_head_invalid')
        validate_document(head['document'])
        if (head['sha256'] != digest(head['document'])
                or not isinstance(head['previous_chain_sha256'], str)
                or not HASH_PATTERN.fullmatch(head['previous_chain_sha256'])
                or not isinstance(head['previous_document_sha256'], str)
                or not HASH_PATTERN.fullmatch(head['previous_document_sha256'])
                or head['chain_sha256'] != chain_digest(self.store_id, head['version'], head['sha256'], head['previous_chain_sha256'])
                or (head['version'] == 0 and (head['document'] != EMPTY_DOCUMENT or head['previous_chain_sha256'] != ZERO_HASH))):
            raise LedgerError('s3_head_corrupt')
        version, checksum = self._observed
        if head['version'] < version or (head['version'] == version and head['sha256'] != checksum):
            raise LedgerError('s3_observed_head_regression')
        self._observed = (head['version'], head['sha256'])
        return head, response['ETag']

    @staticmethod
    def _public(head):
        return {k: head[k] for k in ('store_id', 'version', 'sha256', 'document', 'storage')}

    def _put(self, head, **condition):
        body = canonical(head).encode()
        response = self.client.put_object(**self.target, Body=body, ContentType='application/json',
            ServerSideEncryption='AES256', ChecksumSHA256=base64.b64encode(hashlib.sha256(body).digest()).decode(), **condition)
        if response.get('VersionId') in (None, 'null'):
            raise LedgerError('s3_versioned_write_unconfirmed')

    def _new(self, version, document, previous, previous_document=ZERO_HASH):
        checksum = digest(document)
        return {'store_id': self.store_id, 'version': version, 'sha256': checksum, 'document': document,
                'previous_chain_sha256': previous, 'chain_sha256': chain_digest(self.store_id, version, checksum, previous),
                'storage': 'AWS_S3', 'previous_document_sha256': previous_document}

    def initialize(self):
        """Provisioning-only credentials. Runtime policy permits IfMatch, never IfNoneMatch."""
        try:
            self._versioning()
            self._put(self._new(0, EMPTY_DOCUMENT, ZERO_HASH), IfNoneMatch='*')
            return self.head()
        except (BotoCoreError, ClientError, ValueError, TypeError, KeyError) as error:
            raise LedgerError('s3_initialization_failed') from None

    def head(self):
        with self._lock:
            try:
                return self._public(self._read()[0])
            except (BotoCoreError, ClientError, ValueError, TypeError, KeyError):
                raise LedgerError('s3_head_unavailable_or_invalid') from None

    def append(self, request):
        if (not isinstance(request, dict) or set(request) != {'expected_version', 'expected_sha256', 'document'}
                or not safe_integer(request['expected_version']) or not isinstance(request['expected_sha256'], str)
                or not HASH_PATTERN.fullmatch(request['expected_sha256'])):
            raise InvalidDocument('invalid_version_request')
        validate_document(request['document'])
        with self._lock:
            try:
                head, etag = self._read()
                # A retry after a committed PUT's response was lost is safe only for the exact successor.
                if self._is_retry(head, request):
                    return self._public(head)
                if head['version'] != request['expected_version'] or head['sha256'] != request['expected_sha256']:
                    raise Conflict('stale_version')
                validate_transition(head['document'], request['document'])
                if head['version'] == MAX_INTEGER:
                    raise InvalidDocument('version_space_exhausted')
                following = self._new(head['version'] + 1, request['document'], head['chain_sha256'], head['sha256'])
                try:
                    self._put(following, IfMatch=etag)
                except (BotoCoreError, ClientError):
                    actual, _ = self._read()
                    if actual == following:
                        return self._public(actual)
                    if actual != head:
                        raise Conflict('stale_version')
                    raise LedgerError('s3_write_unconfirmed') from None
                self._observed = (following['version'], following['sha256'])
                return self._public(following)
            except (BotoCoreError, ClientError, ValueError, TypeError, KeyError):
                raise LedgerError('s3_ledger_unavailable_or_invalid') from None

    def _is_retry(self, head, request):
        return (head['version'] == request['expected_version'] + 1
                and head['previous_document_sha256'] == request['expected_sha256']
                and head['document'] == request['document']
                and head['sha256'] == digest(request['document']))



def main():
    from s3_store import load_client
    parser = argparse.ArgumentParser(description='Versioned S3 revision gateway; no cloud provisioning')
    parser.add_argument('command', choices=['init', 'serve'])
    parser.add_argument('--config', type=Path, required=True)
    parser.add_argument('--credentials-file', type=Path, required=True)
    parser.add_argument('--token-file', type=Path)
    parser.add_argument('--bind', default='0.0.0.0')
    parser.add_argument('--port', type=int, default=8088)
    args = parser.parse_args()
    try:
        client, cfg = load_client(args.config, args.credentials_file)
        ledger = S3Ledger(client, cfg['bucket'], cfg['prefix'] + '/head.json', cfg['owner'], cfg['store_id'])
        if args.command == 'init':
            ledger.initialize(); print('S3_LEDGER_INITIALIZED'); return
        ledger.head()
        token = args.token_file.read_bytes().strip()
        if not 32 <= len(token) <= 128 or any(c not in b'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_-' for c in token):
            raise LedgerError('invalid_token_file')
        with LedgerServer((args.bind, args.port), Handler) as server:
            server.ledger, server.authorization = ledger, b'Bearer ' + token
            print('S3_LEDGER_READY', flush=True)
            server.serve_forever()
    except (LedgerError, OSError, ValueError, TypeError, KeyError):
        parser.exit(1, 'external_ledger_unavailable_or_invalid\n')


if __name__ == '__main__': main()
