"""Streaming age archives and immutable S3 transfers; never a DB/RTO acceptance claim.

Only explicitly selected regular files are packed. A consistent DB snapshot and
revision fencing must be produced by the quiesced backup workflow first. Keys
and release credentials are never automatically collected.
"""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import sys
import tarfile
import tempfile
from pyrage import encrypt_io, decrypt_io, x25519

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'infra/external-storage'))
from s3_store import load_client, private_file

CHUNK = 1024 * 1024
MAX_ARCHIVE = 4 * 1024**3  # Single conditional PUT, below S3's 5GB limit. Fail rather than split silently.
MAX_MANIFEST = 1024 * 1024


def sha256_file(path):
    with Path(path).open('rb') as file:
        return hashlib.file_digest(file, 'sha256').hexdigest()


def safe_name(name):
    path = PurePosixPath(name)
    if not name or path.is_absolute() or '..' in path.parts or str(path) != name or '\\' in name or name in ('manifest.json','RESTORE_COMPLETE.json'):
        raise ValueError('unsafe_archive_name')


def _new_output(path):
    # Exclusive reservation prevents overwriting an existing artifact, including symlinks.
    return os.fdopen(os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), 'wb')


def seal_files(files, destination, recipient, metadata):
    destination = Path(destination)
    parsed_recipient = x25519.Recipient.from_str(recipient)
    entries = {}
    for name, path in files.items():
        safe_name(name); path = Path(path)
        if path.is_symlink() or not path.is_file(): raise ValueError('regular_file_required')
        entries[name] = {'size': path.stat().st_size, 'sha256': sha256_file(path)}
    if not entries or sum(e['size'] for e in entries.values()) > MAX_ARCHIVE - 16 * CHUNK:
        raise ValueError('archive_size_limit')
    manifest = json.dumps({'format': 1, 'metadata': metadata, 'files': entries}, sort_keys=True).encode()
    if len(manifest) > MAX_MANIFEST: raise ValueError('manifest_size_limit')
    import io
    with _new_output(destination) as output:
        try:
            with tempfile.TemporaryFile() as plain:
                with tarfile.open(fileobj=plain, mode='w') as tar:
                    info = tarfile.TarInfo('manifest.json'); info.size = len(manifest); info.mode=0o600
                    tar.addfile(info, io.BytesIO(manifest))
                    for name, path in files.items():
                        info = tarfile.TarInfo(name); info.size=entries[name]['size']; info.mode=0o600
                        with Path(path).open('rb') as source: tar.addfile(info, source)
                # Inputs must have remained stable while the snapshot was packed.
                for name, path in files.items():
                    if Path(path).stat().st_size != entries[name]['size'] or sha256_file(path) != entries[name]['sha256']:
                        raise ValueError('snapshot_changed_during_archive')
                plain.seek(0); encrypt_io(plain, output, [parsed_recipient])
            output.flush(); os.fsync(output.fileno())
        except BaseException:
            destination.unlink(missing_ok=True); raise


def unseal_files(archive, destination, identity):
    destination = Path(destination)
    if destination.exists() or destination.is_symlink(): raise FileExistsError(destination)
    if Path(archive).stat().st_size > MAX_ARCHIVE: raise ValueError('archive_size_limit')
    with tempfile.TemporaryDirectory(dir=destination.parent, prefix='.restore-') as staging:
        staging = Path(staging); output = staging/'files'; output.mkdir(mode=0o700)
        with tempfile.TemporaryFile() as plain, Path(archive).open('rb') as encrypted:
            decrypt_io(encrypted, plain, [identity])  # Complete authentication before exposing any plaintext file.
            if plain.tell() > MAX_ARCHIVE: raise ValueError('archive_size_limit')
            plain.seek(0)
            with tarfile.open(fileobj=plain, mode='r:') as tar:
                first = tar.next()
                if first is None or first.name != 'manifest.json' or not first.isfile() or first.size > MAX_MANIFEST:
                    raise ValueError('invalid_archive_manifest')
                manifest = json.load(tar.extractfile(first))
                if manifest.get('format') != 1 or not isinstance(manifest.get('files'), dict): raise ValueError('invalid_archive_manifest')
                seen=set()
                for member in iter(tar.next, None):
                    safe_name(member.name)
                    expected=manifest['files'].get(member.name)
                    if member.name in seen or not member.isfile() or expected is None or member.size != expected['size']:
                        raise ValueError('invalid_archive_member')
                    path=output/member.name; path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
                    with _new_output(path) as target, tar.extractfile(member) as source:
                        shutil.copyfileobj(source,target,CHUNK); target.flush(); os.fsync(target.fileno())
                    if sha256_file(path) != expected['sha256']: raise ValueError('archive_member_hash_mismatch')
                    seen.add(member.name)
                if seen != set(manifest['files']): raise ValueError('archive_members_missing')
        # Reserve the final name exclusively; no other directory is ever replaced.
        destination.mkdir(mode=0o700)
        try:
            for path in output.iterdir(): shutil.move(str(path), destination/path.name)
            # A crash before this marker leaves an incomplete directory; never consume it for recovery.
            with _new_output(destination/'RESTORE_COMPLETE.json') as marker:
                marker.write(json.dumps({'format':1,'files':manifest['files']}).encode()); marker.flush(); os.fsync(marker.fileno())
            fd=os.open(destination,os.O_RDONLY)
            try: os.fsync(fd)
            finally: os.close(fd)
        except BaseException:
            shutil.rmtree(destination); raise
        return manifest['metadata']


class BackupStore:
    def __init__(self, client, bucket, prefix, owner):
        self.client,self.bucket,self.prefix,self.owner=client,bucket,prefix,owner

    def _target(self, name):
        if not re.fullmatch(r'(daily|protected|evidence|logs)/[A-Za-z0-9_-]+\.age', name):
            raise ValueError('scoped_unique_backup_name_required')
        return {'Bucket':self.bucket,'Key':self.prefix+'/'+name,'ExpectedBucketOwner':self.owner}

    def _versioning(self):
        if self.client.get_bucket_versioning(Bucket=self.bucket,ExpectedBucketOwner=self.owner).get('Status') != 'Enabled':
            raise ValueError('s3_versioning_required')

    def upload(self, archive, name, key_ref):
        if not re.fullmatch(r'[A-Za-z0-9_-]{1,100}',key_ref): raise ValueError('invalid_key_ref')
        archive=Path(archive); size=archive.stat().st_size
        if archive.is_symlink() or not 0 < size <= MAX_ARCHIVE: raise ValueError('archive_size_or_type_invalid')
        with archive.open('rb') as file:
            if file.read(22) != b'age-encryption.org/v1\n':
                # Header is 22 bytes including newline; no plaintext upload path.
                raise ValueError('age_archive_required')
        checksum=sha256_file(archive); self._versioning()
        with archive.open('rb') as file:
            result=self.client.put_object(**self._target(name),Body=file,IfNoneMatch='*',ContentType='application/octet-stream',
                ServerSideEncryption='AES256',ChecksumSHA256=base64.b64encode(bytes.fromhex(checksum)).decode())
        version=result.get('VersionId')
        if not version or version=='null': raise ValueError('versioned_upload_unconfirmed')
        receipt={'result':'UPLOADED_VERIFIED_CIPHERTEXT','bucket':self.bucket,'owner':self.owner,'prefix':self.prefix,
                 'name':name,'version_id':version,'ciphertext_sha256':checksum,'bytes':size,'key_ref':key_ref,
                 'database_restore_verified':False}
        # Confirmation is an independent version-pinned read, not trust in an upload response.
        self._read_verified(receipt, None)
        return receipt

    def _read_verified(self, receipt, output):
        if (receipt['bucket'],receipt['owner'],receipt['prefix']) != (self.bucket,self.owner,self.prefix):
            raise ValueError('backup_store_identity_mismatch')
        if not receipt['version_id'] or receipt['version_id']=='null' or not 0<receipt['bytes']<=MAX_ARCHIVE:
            raise ValueError('invalid_backup_receipt')
        response=self.client.get_object(**self._target(receipt['name']),VersionId=receipt['version_id'])
        sha=hashlib.sha256(); size=0
        with response['Body'] as source:
            while data:=source.read(CHUNK):
                size+=len(data)
                if size>receipt['bytes']: raise ValueError('backup_size_mismatch')
                sha.update(data)
                if output: output.write(data)
        if size != receipt['bytes'] or sha.hexdigest()!=receipt['ciphertext_sha256']:
            raise ValueError('backup_ciphertext_mismatch')

    def download(self, receipt, destination):
        destination=Path(destination)
        with _new_output(destination) as output:
            try:
                self._read_verified(receipt,output); output.flush(); os.fsync(output.fileno())
            except BaseException:
                destination.unlink(missing_ok=True); raise


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    commands=parser.add_subparsers(dest='command',required=True)
    seal=commands.add_parser('seal'); seal.add_argument('--files',type=Path,required=True); seal.add_argument('--recipient-file',type=Path,required=True); seal.add_argument('--output',type=Path,required=True); seal.add_argument('--key-ref',required=True)
    restore=commands.add_parser('unseal'); restore.add_argument('--archive',type=Path,required=True); restore.add_argument('--identity-file',type=Path,required=True); restore.add_argument('--output',type=Path,required=True)
    for action in ('upload','download'):
        cmd=commands.add_parser(action); cmd.add_argument('--config',type=Path,required=True); cmd.add_argument('--credentials-file',type=Path,required=True); cmd.add_argument('--receipt',type=Path,required=True)
        if action=='upload': cmd.add_argument('--archive',type=Path,required=True); cmd.add_argument('--name',required=True); cmd.add_argument('--key-ref',required=True)
        else: cmd.add_argument('--output',type=Path,required=True)
    args=parser.parse_args()
    try:
        if args.command=='seal':
            seal_files(json.loads(args.files.read_text()),args.output,args.recipient_file.read_text().strip(),{'key_ref':args.key_ref}); print('ARCHIVE_SEALED')
        elif args.command=='unseal':
            unseal_files(args.archive,args.output,x25519.Identity.from_str(private_file(args.identity_file).read_text().strip())); print('ARCHIVE_AUTHENTICATED_AND_EXTRACTED_NOT_DB_RESTORED')
        else:
            client,cfg=load_client(args.config,args.credentials_file); store=BackupStore(client,cfg['bucket'],cfg['prefix'],cfg['owner'])
            if args.command=='upload':
                if args.receipt.exists(): raise FileExistsError()
                receipt=store.upload(args.archive,args.name,args.key_ref)
                with _new_output(args.receipt) as file: file.write((json.dumps(receipt,indent=2)+'\n').encode())
                print('UPLOADED_VERIFIED_CIPHERTEXT')
            else:
                store.download(json.loads(args.receipt.read_text()),args.output); print('PINNED_CIPHERTEXT_VERIFIED')
    except Exception:
        parser.exit(1,'external_backup_failed_no_success_status_written\n')


if __name__=='__main__': main()
