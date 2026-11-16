#!/usr/bin/env python3
"""Virtual state replay, not AI inference or a measurement of five real minutes."""
import argparse, copy, fcntl, json, signal, sqlite3, time, uuid
from datetime import datetime, timedelta, timezone
from pathlib import Path
import paho.mqtt.client as mqtt
from jsonschema import Draft202012Validator, FormatChecker
ROOT=Path(__file__).resolve().parents[1]
CONTRACT=ROOT/'reference/v1.43/개발자료/09_구현설계/contracts'
SCHEMA=json.loads((CONTRACT/'parking.schema.json').read_text())
EXAMPLES=json.loads((CONTRACT/'examples.json').read_text())
def validator(name):return Draft202012Validator({'$schema':SCHEMA['$schema'],'$defs':SCHEMA['$defs'],'$ref':f'#/$defs/{name}'},format_checker=FormatChecker())
VALIDATORS={n:validator(n) for n in ('EventMessage','Heartbeat','Ack')}
def utc(now=None):return (now or datetime.now(timezone.utc)).isoformat(timespec='milliseconds').replace('+00:00','Z')
class State:
    def __init__(self,path):
        path=Path(path);path.parent.mkdir(parents=True,exist_ok=True)
        self.lock=open(str(path)+'.lock','a');fcntl.flock(self.lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
        self.db=sqlite3.connect(path);self.db.execute('PRAGMA journal_mode=WAL');self.db.execute('PRAGMA synchronous=FULL')
        self.db.executescript('CREATE TABLE IF NOT EXISTS counter(id INTEGER PRIMARY KEY CHECK(id=1),seq INTEGER NOT NULL); INSERT OR IGNORE INTO counter VALUES(1,0); CREATE TABLE IF NOT EXISTS outbox(id TEXT PRIMARY KEY,payload TEXT NOT NULL,status TEXT NOT NULL DEFAULT "PENDING",error TEXT);')
    def seq(self):
        with self.db:
            self.db.execute('UPDATE counter SET seq=seq+1 WHERE id=1');seq=self.db.execute('SELECT seq FROM counter').fetchone()[0]
            if seq>9007199254740991:raise RuntimeError('sequence exhausted')
            return seq
    def queue(self,message):
        VALIDATORS['EventMessage'].validate(message)
        payload=json.dumps(message,separators=(',',':'),ensure_ascii=False)
        with self.db:self.db.execute('INSERT INTO outbox(id,payload) VALUES(?,?)',(message['message_id'],payload))
    def pending(self):return self.db.execute('SELECT id,payload FROM outbox WHERE status="PENDING" ORDER BY rowid').fetchall()
    def acknowledge(self,body):
        VALIDATORS['Ack'].validate(body)
        if body['device_id']!='edge-01':return
        with self.db:
            if body['status'] in ('STORED','DUPLICATE'):self.db.execute('DELETE FROM outbox WHERE id=? AND status="PENDING"',(body['message_id'],))
            else:self.db.execute('UPDATE outbox SET status="REJECTED",error=? WHERE id=?',(body['error_code'],body['message_id']))
    def close(self):self.db.close();self.lock.close()
def event(state,scenario):
    message=copy.deepcopy(EXAMPLES['guidance_complete']);now=datetime.now(timezone.utc)
    message['message_id']='demo-'+uuid.uuid4().hex;message['event_id']='demo-'+uuid.uuid4().hex[:12]
    message['boot_id']='sim-'+uuid.uuid4().hex[:16];message['device_seq']=state.seq();message['occurred_at']=utc(now)
    snapshot=message['snapshot'];snapshot.update(current_event_id=message['event_id'],boot_id=message['boot_id'],device_seq=message['device_seq'],observed_at=utc(now))
    snapshot['clock_status'].update(last_sync_at=utc(now),sync_age_ms=0)
    record=message['event'];record.update(event_id=message['event_id'],started_at=utc(now-timedelta(milliseconds=304000)))
    if scenario=='held':
        message['event_type']='OBSERVATION_HELD';record['state']='HELD';snapshot.update(incident_state='HELD',camera_health='BLOCKED',reason_codes=['OCCLUSION_SUSPECT'])
    elif scenario=='exit':
        message['event_type']='EVENT_CLOSED_EXIT';record.update(state='CLOSED_EXIT',closed_at=utc(now),close_reason='OBSERVED_EXIT');snapshot['incident_state']='CLOSED_EXIT'
    VALIDATORS['EventMessage'].validate(message);return message

def configure_transport(client, ca_file=None, username=None, password_file=None):
    if any((ca_file, username, password_file)):
        if not all((ca_file, username, password_file)):
            raise ValueError('TLS CA, username and password file must be supplied together')
        password=Path(password_file).read_text().rstrip('\r\n')
        if not password or '\n' in password or '\r' in password:
            raise ValueError('invalid MQTT password file')
        client.tls_set(ca_certs=str(ca_file))
        client.tls_insecure_set(False)
        client.username_pw_set(username,password)

def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--host',default='127.0.0.1');parser.add_argument('--port',type=int,default=1883);parser.add_argument('--state',default=str(ROOT/'.local-state/handoff/simulator.sqlite'));parser.add_argument('--scenario',choices=['guided','held','exit'],default='guided');parser.add_argument('--seconds',type=float,default=0);parser.add_argument('--event-id');parser.add_argument('--tls-ca');parser.add_argument('--username');parser.add_argument('--password-file');args=parser.parse_args()
    client=mqtt.Client(mqtt.CallbackAPIVersion.VERSION2,client_id='sim-'+uuid.uuid4().hex[:12],protocol=mqtt.MQTTv5)
    configure_transport(client,args.tls_ca,args.username,args.password_file)
    state=State(args.state);stopping=False;ready=False
    def stop(*_):nonlocal stopping;stopping=True
    signal.signal(signal.SIGTERM,stop);signal.signal(signal.SIGINT,stop)
    message=event(state,args.scenario)
    if args.event_id:
        message['event_id']=args.event_id;message['event']['event_id']=args.event_id;message['snapshot']['current_event_id']=args.event_id
    state.queue(message)
    client.max_queued_messages_set(20);client.max_inflight_messages_set(8);client.reconnect_delay_set(1,30)
    def connected(client,_userdata,_flags,reason,_properties):
        if not reason.is_failure:client.subscribe('parking/v2/devices/edge-01/acks',qos=1)
    def subscribed(_client,_userdata,_mid,reasons,_properties):
        nonlocal ready;ready=not any(r.is_failure for r in reasons)
    def disconnected(*_):nonlocal ready;ready=False
    def received(_client,_userdata,incoming):
        if incoming.topic!='parking/v2/devices/edge-01/acks' or incoming.retain:return
        try:
            ack=json.loads(incoming.payload);state.acknowledge(ack)
            print(json.dumps({'kind':'APPLICATION_ACK','message_id':ack['message_id'],'status':ack['status'],'event_id':message['event_id']}),flush=True)
        except (ValueError,KeyError,TypeError) as error:print('IGNORED_ACK '+type(error).__name__,flush=True)
        except Exception as error:print('ACK_VALIDATION_FAILED '+type(error).__name__,flush=True)
    client.on_connect=connected;client.on_subscribe=subscribed;client.on_disconnect=disconnected;client.on_message=received
    print(json.dumps({'kind':'VIRTUAL_REPLAY','event_id':message['event_id'],'scenario':args.scenario,'real_ai':False}),flush=True)
    started=time.monotonic();next_publish=0;next_heartbeat=0;reconnect_at=0
    try:
        while not stopping and (not args.seconds or time.monotonic()-started<args.seconds):
            if not client.is_connected() and time.monotonic()>=reconnect_at:
                reconnect_at=time.monotonic()+2
                try:client.connect(args.host,args.port,keepalive=15,clean_start=True)
                except OSError:pass
            result=client.loop(timeout=.2)
            if result!=mqtt.MQTT_ERR_SUCCESS:time.sleep(.1)
            now=time.monotonic()
            if ready and now>=next_publish:
                for _,payload in state.pending()[:8]:client.publish('parking/v2/devices/edge-01/events',payload,qos=1,retain=False)
                next_publish=now+5
            if ready and now>=next_heartbeat:
                heartbeat=copy.deepcopy(EXAMPLES['heartbeat']);seq=state.seq();now_utc=utc()
                heartbeat.update(message_id='hb-'+uuid.uuid4().hex,device_seq=seq,boot_id=message['boot_id'],event_id=message['event_id'],occurred_at=now_utc,snapshot=copy.deepcopy(message['snapshot']),pending_message_count=len(state.pending()))
                heartbeat['snapshot'].update(device_seq=seq,observed_at=now_utc)
                heartbeat['snapshot']['clock_status'].update(last_sync_at=now_utc,sync_age_ms=0)
                VALIDATORS['Heartbeat'].validate(heartbeat)
                client.publish('parking/v2/devices/edge-01/heartbeat',json.dumps(heartbeat),qos=0,retain=False);next_heartbeat=now+5
    finally:
        client.disconnect();state.close()
if __name__=='__main__':main()
