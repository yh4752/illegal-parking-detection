"""Offline certificate validation/staging only; never requests or installs a certificate."""
import argparse
import datetime as dt
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
from cryptography import x509
from cryptography.hazmat.primitives import serialization

ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'infra/external-storage'))
from s3_store import private_file


def stage(chain,private_key,trust,domain,output):
    chain,private_key,trust,output=map(Path,(chain,private_key,trust,output))
    if not re.fullmatch(r'[a-z0-9][a-z0-9.-]*\.[a-z]{2,63}',domain):raise ValueError('invalid_domain')
    if any(p.stat().st_size>1024*1024 for p in (chain,private_key,trust)):raise ValueError('tls_input_too_large')
    chain_bytes=chain.read_bytes()
    key_bytes=private_file(private_key).read_bytes()
    trust_bytes=chain_bytes if trust==chain else trust.read_bytes()
    if any(len(data)>1024*1024 for data in (chain_bytes,key_bytes,trust_bytes)):raise ValueError("tls_input_too_large")
    certificate=x509.load_pem_x509_certificate(chain_bytes)
    key=serialization.load_pem_private_key(key_bytes,password=None)
    now=dt.datetime.now(dt.timezone.utc)
    if certificate.not_valid_before_utc>now or certificate.not_valid_after_utc<now+dt.timedelta(days=1):raise ValueError('certificate_not_current_or_expiring')
    try:names=certificate.extensions.get_extension_for_class(x509.SubjectAlternativeName).value.get_values_for_type(x509.DNSName)
    except x509.ExtensionNotFound:raise ValueError('dns_san_required') from None
    if domain not in names:raise ValueError('exact_domain_san_required')
    def public(k):return k.public_bytes(serialization.Encoding.DER,serialization.PublicFormat.SubjectPublicKeyInfo)
    if public(key.public_key())!=public(certificate.public_key()):raise ValueError('certificate_private_key_mismatch')
    # Validate immutable snapshots, then stage those exact bytes even during renewal.
    with tempfile.TemporaryDirectory(prefix='parking-cert-check-') as folder:
        snapshot=Path(folder)/'chain.pem';snapshot.write_bytes(chain_bytes)
        authority=Path(folder)/'trust.pem';authority.write_bytes(trust_bytes)
        check=subprocess.run(['openssl','verify','-purpose','sslserver','-CAfile',str(authority),'-untrusted',str(snapshot),str(snapshot)],capture_output=True,timeout=15)
    if check.returncode:raise ValueError('certificate_chain_invalid')
    output.mkdir(mode=0o700)
    for name,data,mode in [('server.crt',chain_bytes,0o644),('server.key',key_bytes,0o600)]:
        with os.fdopen(os.open(output/name,os.O_WRONLY|os.O_CREAT|os.O_EXCL,mode),'wb') as file:file.write(data)
    report={'result':'CERTIFICATE_STAGED_NOT_INSTALLED','domain':domain,'expires_at':certificate.not_valid_after_utc.isoformat()}
    (output/'report.json').write_text(json.dumps(report,indent=2)+'\n')
    return report


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    for flag in ('chain','private-key','trust','output'):parser.add_argument('--'+flag,type=Path,required=True)
    parser.add_argument('--domain',required=True);args=parser.parse_args()
    try:print(json.dumps(stage(args.chain,args.private_key,args.trust,args.domain,args.output)))
    except Exception:parser.exit(1,'certificate_stage_failed_existing_installation_unchanged\n')


if __name__=='__main__':main()
