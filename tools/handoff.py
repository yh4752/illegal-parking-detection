#!/usr/bin/env python3
"""Isolated local handoff stack. Never removes volumes, resets a DB or changes parking-local."""
import argparse, base64, json, os, secrets, subprocess, sys, time, urllib.request
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
STATE=ROOT/'.local-state/handoff'
ENV=STATE/'.env'
def run(*args): subprocess.run(args,cwd=ROOT,check=True)
def compose(*args): run('docker','compose','--env-file',str(ENV),'-f','infra/handoff/compose.yaml',*args)
def wait_ready():
    for _ in range(90):
        try:
            with urllib.request.urlopen('http://127.0.0.1:18080/actuator/health',timeout=2) as r:
                if json.load(r).get('status')=='UP': return
        except (OSError,ValueError): pass
        time.sleep(1)
    raise SystemExit('Backend did not become ready. Run: python3 tools/handoff.py logs')
def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('command',choices=['prepare','start','stop','status','logs','simulate','pause-simulator']);args=p.parse_args()
    if args.command=='prepare':
        STATE.mkdir(parents=True,exist_ok=True);os.chmod(STATE,0o700)
        if not ENV.exists():
            # Exclusive creation; output contains paths, never credentials.
            fd=os.open(ENV,os.O_CREAT|os.O_EXCL|os.O_WRONLY,0o600)
            with os.fdopen(fd,'w') as f:f.write(f'POSTGRES_PASSWORD={secrets.token_urlsafe(32)}\nPARKING_CURSOR_HMAC_KEY={base64.b64encode(secrets.token_bytes(32)).decode()}\n')
        password=STATE/'web-password'
        if not password.exists():
            fd=os.open(password,os.O_CREAT|os.O_EXCL|os.O_WRONLY,0o600)
            with os.fdopen(fd,'w') as f:f.write(secrets.token_urlsafe(20)+'\n')
        print('Local secrets prepared. Username: handoff-viewer. Password file: .local-state/handoff/web-password',flush=True)
        compose('build','backend','web','simulator');compose('up','-d','--wait','postgres','mosquitto')
        marker=STATE/'initialized'
        if not marker.exists():
            compose('run','--rm','bootstrap');marker.write_text('parking-handoff initialized\n')
        else: print('Existing handoff database kept; bootstrap skipped.',flush=True)
    elif not ENV.exists(): raise SystemExit('Run python3 tools/handoff.py prepare first.')
    if args.command in ('prepare','start'):
        compose('up','-d','postgres','mosquitto','backend','web');wait_ready();print('Open http://127.0.0.1:18081 . No virtual event yet; run simulate explicitly.')
    elif args.command=='simulate':compose('--profile','simulation','up','-d','simulator')
    elif args.command=='pause-simulator':compose('stop','simulator')
    elif args.command=='stop':compose('--profile','simulation','stop')
    elif args.command=='status':compose('--profile','simulation','ps')
    elif args.command=='logs':compose('logs','--tail','250','backend','web')
if __name__=='__main__':
    try:main()
    except subprocess.CalledProcessError as e:sys.exit(e.returncode)
