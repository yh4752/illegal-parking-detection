"""Explicit credentials, pinned bucket owner, bounded retries, AWS TLS/SigV4 only."""
import configparser
import json
import re
import stat
from pathlib import Path
import boto3
from botocore.config import Config


def private_file(path):
    path = Path(path)
    mode = path.lstat().st_mode
    if not stat.S_ISREG(mode) or mode & 0o077:
        raise ValueError('owner_only_regular_secret_required')
    return path


def validate_config(cfg):
    if (not isinstance(cfg, dict) or set(cfg) != {'bucket', 'prefix', 'owner', 'region', 'store_id'}
            or not re.fullmatch(r'[a-z0-9][a-z0-9-]{1,61}[a-z0-9]', cfg['bucket'])
            or not re.fullmatch(r'[a-zA-Z0-9_-]+(?:/[a-zA-Z0-9_-]+)*', cfg['prefix'])
            or not re.fullmatch(r'[0-9]{12}', cfg['owner'])
            or not re.fullmatch(r'(us|eu|ap|sa|ca|me|af|il|mx)-(?:[a-z]+-)+[0-9]', cfg['region'])):
        raise ValueError('invalid_s3_config')
    import uuid
    if str(uuid.UUID(cfg['store_id'])) != cfg['store_id']:
        raise ValueError('invalid_store_identity')
    return cfg


def load_client(config_file, credentials_file):
    cfg = validate_config(json.loads(Path(config_file).read_text()))
    credentials = configparser.ConfigParser(interpolation=None)
    credentials.read_string(private_file(credentials_file).read_text())
    if set(credentials.sections()) != {'parking'} or credentials.defaults():
        raise ValueError('explicit_parking_credentials_required')
    section = dict(credentials['parking'])
    if not {'aws_access_key_id', 'aws_secret_access_key'} <= section.keys() or not section.keys() <= {'aws_access_key_id', 'aws_secret_access_key', 'aws_session_token'}:
        raise ValueError('explicit_parking_credentials_required')
    if any(not v.strip() for v in section.values()):
        raise ValueError('empty_s3_credential')
    # Explicit keys prevent ambient profiles, IMDS, environment credentials and endpoint overrides.
    client = boto3.client('s3', **section, region_name=cfg['region'],
        endpoint_url=f"https://s3.{cfg['region']}.amazonaws.com", verify=True,
        config=Config(signature_version='s3v4', connect_timeout=2, read_timeout=5,
                      retries={'mode': 'standard', 'total_max_attempts': 2},
                      proxies={}, s3={'addressing_style': 'virtual'}, ignore_configured_endpoint_urls=True))
    return client, cfg
