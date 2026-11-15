#!/usr/bin/env python3
"""Real isolated stack failure checks. Run after browser test; touches parking-handoff only."""
import copy,http.cookiejar,json,subprocess,time,urllib.request,urllib.error,uuid
from datetime import datetime,timedelta,timezone
from pathlib import Path
from handoff import ROOT,ENV,STATE,compose,wait_ready
BASE='http://127.0.0.1:18080'
COMMAND=['docker','compose','--env-file',str(ENV),'-f',str(ROOT/'infra/handoff/compose.yaml')]
def capture(*args,input=None,timeout=60):
    result=subprocess.run(COMMAND+list(args),input=input,text=True,capture_output=True,cwd=ROOT,timeout=timeout)
    if result.returncode:raise RuntimeError(result.stderr[-3000:])
    return result.stdout.strip()
def probe(*messages):
    return json.loads(capture('run','--rm','-T','--no-deps','--entrypoint','python','simulator','simulator/transport_probe.py',input=json.dumps({'messages':list(messages)}),timeout=90))
class API:
    def __init__(self):self.client=urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
    def get(self,path):
        with self.client.open(BASE+path,timeout=10) as response:return json.load(response)
    def login(self):
        token=self.get('/api/csrf')['token']
        body=json.dumps({'username':'handoff-viewer','password':(STATE/'web-password').read_text().strip()}).encode()
        with self.client.open(urllib.request.Request(BASE+'/api/auth/login',data=body,headers={'Content-Type':'application/json','X-CSRF-TOKEN':token}),timeout=10) as response:assert response.status==200
    def zone(self):return self.get('/api/zones/zone-01/status')
compose('stop','simulator')
api=API();api.login()
# Reserve real producer sequence numbers while its process is stopped. No counter reset.
reserve='import sys,json; sys.path.insert(0,"simulator"); from device import State; s=State("/state/device.sqlite"); print(json.dumps([s.seq() for _ in range(4)])); s.close()'
sequence=json.loads(capture('run','--rm','-T','--no-deps','--entrypoint','python','simulator','-c',reserve))
seq=sequence[0];assert sequence==list(range(seq,seq+4))
fixture=json.loads((ROOT/'reference/v1.43/개발자료/09_구현설계/contracts/examples.json').read_text())['guidance_complete']
event_id='transport-'+uuid.uuid4().hex[:12]
def event(message_id,number):
    result=copy.deepcopy(fixture);now=datetime.now(timezone.utc);stamp=lambda x:x.isoformat(timespec='milliseconds').replace('+00:00','Z')
    result.update(message_id=message_id,event_id=event_id,device_seq=number,occurred_at=stamp(now))
    result['event'].update(event_id=event_id,started_at=stamp(now-timedelta(seconds=304)))
    result['snapshot'].update(current_event_id=event_id,device_seq=number,observed_at=stamp(now))
    result['snapshot']['clock_status'].update(last_sync_at=stamp(now),sync_age_ms=0)
    return result
prefix=uuid.uuid4().hex[:10];first=event('probe-'+prefix,seq)
print('TRANSPORT: normal, duplicate, conflict, reverse order',flush=True)
acks=probe({'payload':first,'expected':['STORED']},{'payload':first,'expected':['DUPLICATE']},
           {'payload':event(first['message_id'],seq+1),'expected':['REJECTED'],'error':'MESSAGE_ID_CONFLICT'},
           {'payload':event('high-'+prefix,seq+2),'expected':['STORED']},
           {'payload':event('low-'+prefix,seq+1),'expected':['STORED']})
assert api.zone()['snapshot']['device_seq']==seq+2
records=api.get('/api/events/'+event_id)['messages'];assert sorted(x['device_seq'] for x in records)==[seq,seq+1,seq+2]
print('TRANSPORT: database down gives no storage ACK; replay recovers exactly one row',flush=True)
failed=event('db-down-'+prefix,seq+3)
compose('stop','postgres')
try:probe({'payload':failed,'expected':None,'timeout':4})
finally:compose('up','-d','--wait','postgres')
probe({'payload':failed,'expected':['STORED','DUPLICATE'],'timeout':40})
records=api.get('/api/events/'+event_id)['messages'];assert sum(x['message_id']==failed['message_id'] for x in records)==1
print('TRANSPORT: two broker outage cycles retain outbox and recover real application ACKs',flush=True)
for cycle in range(1,3):
    compose('stop','simulator')
    compose('stop','mosquitto')
    try:
        compose('--profile','simulation','up','-d','--no-deps','simulator')
        time.sleep(3)
        code='import sqlite3; c=sqlite3.connect("/state/device.sqlite"); print(c.execute("SELECT count(*) FROM outbox WHERE status=\'PENDING\'").fetchone()[0])'
        assert int(capture('exec','-T','simulator','python','-c',code))>0
    finally:compose('up','-d','mosquitto')
    started=time.monotonic();end=started+50
    while time.monotonic()<end:
        if int(capture('exec','-T','simulator','python','-c',code))==0 and api.zone()['connection_state']=='ONLINE':break
        time.sleep(1)
    else:raise AssertionError(f'broker cycle {cycle}: no stored ACK and ONLINE recovery within 50 seconds')
    print(f'BROKER_RECOVERY_CYCLE={cycle} elapsed_seconds={time.monotonic()-started:.1f}',flush=True)
    assert api.zone()['connection_state']=='ONLINE'
compose('stop','simulator')
print('TRANSPORT: backend restart preserves history and invalidates old sessions',flush=True)
compose('restart','backend');wait_ready()
try:api.get('/api/auth/me');raise AssertionError('old session survived restart')
except urllib.error.HTTPError as error:assert error.code==401
api=API();api.login();records=api.get('/api/events/'+event_id)['messages'];assert len(records)==4
print('HANDOFF_TRANSPORT_PASSED scenarios=normal,duplicate,conflict,reverse,db_outage,broker_outage,restart',flush=True)
