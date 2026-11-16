#!/usr/bin/env python3
"""Isolated O1 Docker acceptance test. Never uses parking-local or handoff volumes.
Creates disposable test credentials under .local-state; never a deploy command.
"""
import json, secrets, shutil, ssl, subprocess, sys, time, uuid
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'simulator'))
import paho.mqtt.client as mqtt

PG='postgres:17.11@sha256:ff87138949ad9a46f32ff23bd6852b52ce024a13dffe9b4087be98292cdd3b4f'
MQ='eclipse-mosquitto:2.0.22@sha256:81a6193ae693942a3231cd3ac52875b02b7e3a19da671b2fb4b55cc874c5b279'
CASE=ROOT/'.local-state/o1'/uuid.uuid4().hex
CASE.mkdir(parents=True,mode=0o700); PROJECT='parking-o1-'+CASE.name[:8]
SECRET_VALUES=[]

def redact(text):
    for value in SECRET_VALUES: text=text.replace(value,'[redacted]')
    return text

def run(*args,input=None,timeout=180,include_stderr=False):
    try:p=subprocess.run(args,input=input,text=True,capture_output=True,cwd=ROOT,timeout=timeout)
    except subprocess.TimeoutExpired:raise RuntimeError('Command timed out: '+args[0]) from None
    if p.returncode:raise RuntimeError('Command failed: '+args[0]+'\n'+redact((p.stdout+p.stderr)[-6000:]))
    return p.stdout+p.stderr if include_stderr else p.stdout

def dc(*args,input=None,timeout=180):return run('docker','compose','-p',PROJECT,'-f',str(CASE/'compose.json'),*args,input=input,timeout=timeout)
def sql(statement,user='postgres'):
    return dc('exec','-T','postgres','psql','-X','-v','ON_ERROR_STOP=1','-v','VERBOSITY=verbose','-U',user,'-d','parking_handoff','-At',input=statement)

def denied_sql(statement,user):
    # Connect as the restricted role. SET ROLE after a superuser login retains SET ROLE authority.
    try:sql(statement,user)
    except RuntimeError as error:
        assert '42501' in str(error), 'Expected insufficient_privilege, not a syntax/constraint failure'
    else:raise AssertionError('Permission unexpectedly allowed: '+statement)
def wait_until(check,seconds=60):
    deadline=time.monotonic()+seconds
    while time.monotonic()<deadline:
        try:
            if check():return
        except (RuntimeError,OSError):pass
        time.sleep(.5)
    raise AssertionError('condition timed out')
def certificate(name,ext=None):
    run('openssl','req','-new','-newkey','rsa:2048','-nodes','-keyout',str(CASE/(name+'.key')),'-out',str(CASE/(name+'.csr')),'-subj','/CN='+name)
    if ext:
        run('openssl','x509','-req','-in',str(CASE/(name+'.csr')),'-CA',str(CASE/'ca.crt'),'-CAkey',str(CASE/'ca.key'),'-CAcreateserial','-out',str(CASE/(name+'.crt')),'-days','2','-extfile',str(ext))
    else:
        ca_ext=CASE/(name+'.ext')
        ca_ext.write_text('basicConstraints=critical,CA:TRUE\nkeyUsage=critical,keyCertSign,cRLSign\nsubjectKeyIdentifier=hash\n')
        run('openssl','x509','-req','-in',str(CASE/(name+'.csr')),'-signkey',str(CASE/(name+'.key')),'-out',str(CASE/(name+'.crt')),'-days','2','-extfile',str(ca_ext))

def mqtt_client(host,port,user,password,ca='ca.crt',expect_failure=False):
    c=mqtt.Client(mqtt.CallbackAPIVersion.VERSION2,protocol=mqtt.MQTTv5,client_id='probe-'+uuid.uuid4().hex[:10])
    c.tls_set(ca_certs=str(CASE/ca));c.tls_insecure_set(False)
    if user:c.username_pw_set(user,password)
    result=[]
    c.on_connect=lambda _c,_u,_f,reason,_p:result.append(not reason.is_failure)
    try:c.connect(host,port,keepalive=10)
    except ssl.SSLError:
        if expect_failure:return None
        raise
    deadline=time.monotonic()+5
    while not result and time.monotonic()<deadline:c.loop(timeout=.1)
    if expect_failure:
        c.disconnect();assert result==[False], 'Expected authentication rejection';return None
    assert result==[True], 'Expected authenticated TLS connection'
    return c

def denied_publish(client,topic):
    results=[];client.on_publish=lambda _c,_u,_m,reason,_p:results.append(reason.is_failure)
    client.publish(topic,'{"test":true}',qos=1)
    deadline=time.monotonic()+5
    while not results and time.monotonic()<deadline:client.loop(timeout=.1)
    assert results==[True], 'Unauthorized publish must receive failing PUBACK'

def rejected_backend(markers,*options,service='backend',before_db=False,broker_markers=()):
    broker_before=dc('logs','mosquitto') if broker_markers else ''
    container=dc('run','-d','--no-deps',*options,service).strip()
    try:
        networks=json.loads(run('docker','inspect','--format','{{json .NetworkSettings.Networks}}',container))
        address=next(iter(networks.values()))['IPAddress']
        deadline=time.monotonic()+30
        while time.monotonic()<deadline:
            logs=run('docker','logs',container,include_stderr=True)
            assert 'EVENT_INGEST_READY' not in logs, 'Invalid configuration must not subscribe'
            if any(marker in logs for marker in markers):
                if before_db:assert 'HikariPool-1 - Starting' not in logs
                return
            if broker_markers:
                # Automatic reconnect does not log the initial Paho exception.
                # Attribute fresh broker evidence to this JVM container, and wait
                # until all lifecycle starts have run; no authenticated channel may appear.
                broker_logs=dc('logs','mosquitto')[len(broker_before):]
                assert 'New client connected from '+address+':' not in broker_logs, 'Invalid JVM transport connected'
                attempted='New connection from '+address+':' in broker_logs
                rejected=any(marker.lower() in broker_logs.lower() for marker in broker_markers)
                if attempted and rejected and 'Started ParkingApplicationKt' in logs:return
            time.sleep(.25)
        raise AssertionError('Expected backend rejection was not observed: '+redact(logs[-4000:]))
    finally:run('docker','rm','-f',container)

def verify_ingestion(port,password):
    client=mqtt_client('localhost',port,'edge-01',password)
    acknowledgments=[];subscribed=[]
    client.on_message=lambda _c,_u,m:acknowledgments.append(json.loads(m.payload))
    client.on_subscribe=lambda _c,_u,_m,reasons,_p:subscribed.append(not any(r.is_failure for r in reasons))
    client.subscribe('parking/v2/devices/edge-01/acks',qos=1)
    def pump(seconds,condition):
        deadline=time.monotonic()+seconds
        while time.monotonic()<deadline and not condition():client.loop(.1)
    def send(message,expected):
        acknowledgments.clear()
        client.publish('parking/v2/devices/edge-01/events',json.dumps(message),qos=1)
        pump(5,lambda:bool(acknowledgments))
        assert len(acknowledgments)==1 and acknowledgments[0]['message_id']==message['message_id']
        assert acknowledgments[0]['status']==expected
    try:
        pump(5,lambda:bool(subscribed));assert subscribed==[True]
        original=json.loads(sql('SELECT payload FROM event_message LIMIT 1').strip())
        send(original,'DUPLICATE')
        conflict=json.loads(json.dumps(original));conflict['event']['body_type']='BUS'
        send(conflict,'REJECTED');assert acknowledgments[0]['error_code']=='MESSAGE_ID_CONFLICT'
        before=sql("SELECT last_device_seq,snapshot FROM zone_view WHERE zone_id='zone-01'")
        late=json.loads(json.dumps(original));late['message_id']='o1-late'
        send(late,'STORED')
        assert sql("SELECT last_device_seq,snapshot FROM zone_view WHERE zone_id='zone-01'")==before
        late['message_id']='o1-db-failure'
        sql('REVOKE INSERT ON event_message FROM parking_runtime')
        try:
            acknowledgments.clear();client.publish('parking/v2/devices/edge-01/events',json.dumps(late),qos=1)
            pump(3,lambda:bool(acknowledgments))
            assert acknowledgments==[], 'Failed transaction must never get a success ACK'
            assert sql("SELECT count(*) FROM inbound_receipt WHERE message_id='o1-db-failure'").strip()=='0'
        finally:sql('GRANT INSERT ON event_message TO parking_runtime')
        send(late,'STORED')
    finally:client.disconnect()
    print('PASS: TLS duplicate/conflict/late messages and DB failure without success ACK, retry commits',flush=True)

def main():
    server_password=secrets.token_hex(24);edge_password=secrets.token_hex(24);db_password=secrets.token_hex(24)
    runtime_password=secrets.token_hex(24);migrator_password=secrets.token_hex(24)
    SECRET_VALUES.extend([server_password,edge_password,db_password,runtime_password,migrator_password])
    certificate('ca');certificate('wrong-ca')
    (CASE/'extensions').write_text('basicConstraints=critical,CA:FALSE\nkeyUsage=critical,digitalSignature,keyEncipherment\nsubjectAltName=DNS:localhost,DNS:mosquitto\nextendedKeyUsage=serverAuth\n')
    certificate('server',CASE/'extensions')
    broker=CASE/'broker';broker.mkdir()
    for name in ['server.crt','server.key']:shutil.copy(CASE/name,broker/name);(broker/name).chmod(0o644)
    for name in ['mosquitto.conf','acl']:shutil.copy(ROOT/'infra/deploy/mosquitto'/name,broker/name)
    (broker/'passwords').write_text('parking-server:'+server_password+'\nedge-01:'+edge_password+'\n');(broker/'passwords').chmod(0o644)
    run('docker','run','--rm','--user','0:0','-v',str(broker)+':/config',MQ,'mosquitto_passwd','-U','/config/passwords')
    sec=CASE/'secrets';sec.mkdir()
    for name,value in {'POSTGRES_PASSWORD':runtime_password,'parking.mqtt.password':server_password,'PARKING_CURSOR_HMAC_KEY':__import__('base64').b64encode(secrets.token_bytes(32)).decode()}.items():
        SECRET_VALUES.append(value);(sec/name).write_text(value);(sec/name).chmod(0o644)
    migration_sec=CASE/'migration-secrets';migration_sec.mkdir()
    (migration_sec/'POSTGRES_PASSWORD').write_text(migrator_password);(migration_sec/'POSTGRES_PASSWORD').chmod(0o644)
    wrong_sec=CASE/'wrong-secrets';shutil.copytree(sec,wrong_sec)
    (wrong_sec/'parking.mqtt.password').write_text('invalid-test-only')
    admin_sec=CASE/'admin-probe-secrets';shutil.copytree(sec,admin_sec)
    (admin_sec/'POSTGRES_PASSWORD').write_text(db_password)
    empty_sec=CASE/'empty-secrets';empty_sec.mkdir()
    web_password=secrets.token_hex(16);SECRET_VALUES.append(web_password)
    (CASE/'edge-password').write_text(edge_password);(CASE/'web-password').write_text(web_password);(CASE/'web-password').chmod(0o644)
    (CASE/'wrong-edge-password').write_text('invalid-test-only')
    base_env={'PARKING_DB_URL':'jdbc:postgresql://postgres:5432/parking_handoff','PARKING_REVISION_PRIMARY':'/state/revisions.json','PARKING_REVISION_BACKUP':'/state-copy/revisions.json'}
    volumes=['revision:/state','revision-copy:/state-copy']
    image='parking-o1-backend:'+CASE.name[:8]
    config={'services':{
      'postgres':{'image':PG,'environment':{'POSTGRES_PASSWORD':db_password,'POSTGRES_DB':'parking_handoff'},'volumes':['db:/var/lib/postgresql/data']},
      'mosquitto':{'image':MQ,'volumes':[str(broker)+':/mosquitto/config:ro','mqtt:/mosquitto/data'],'ports':['127.0.0.1::8883'],'networks':{'default':{'aliases':['wrong-host']}}},
      'bootstrap':{'image':image,'build':{'context':str(ROOT),'dockerfile':'backend/Dockerfile'},'user':'0:0','entrypoint':['/bin/sh','/app/bootstrap.sh'],'environment':{**base_env,'PARKING_DB_USERNAME':'parking_migrator','SPRING_CONFIG_IMPORT':'configtree:/run/migration-secrets/'},'volumes':volumes+[str(CASE/'web-password')+':/run/secrets/web_password:ro',str(migration_sec)+':/run/migration-secrets:ro']},
      'migrate':{'image':image,'entrypoint':['java','-Dloader.main=kr.ac.sejong.parkingtools.MigrateDatabaseCliKt','-cp','/app/app.jar','org.springframework.boot.loader.launch.PropertiesLauncher'],'command':['--migrate'],'environment':{'PARKING_DB_URL':'jdbc:postgresql://postgres:5432/parking_migration_probe','PARKING_DB_USERNAME':'parking_migrator','SPRING_CONFIG_IMPORT':'configtree:/run/secrets/'},'volumes':[str(migration_sec)+':/run/secrets:ro']},
      'backend':{'image':image,'environment':{**base_env,'SPRING_PROFILES_ACTIVE':'app-prod','PARKING_MQTT_URL':'ssl://mosquitto:8883','PARKING_MQTT_CA_FILE':'/tls/ca.crt'},'volumes':volumes+[str(sec)+':/run/secrets:ro',str(CASE/'ca.crt')+':/tls/ca.crt:ro']},
      'simulator':{'build':{'context':str(ROOT),'dockerfile':'simulator/Dockerfile'},'command':['--host','mosquitto','--port','8883','--state','/state/device.sqlite','--tls-ca','/tls/ca.crt','--username','edge-01','--password-file','/run/edge-password','--seconds','12'],'volumes':['sim:/state',str(CASE/'ca.crt')+':/tls/ca.crt:ro',str(CASE/'edge-password')+':/run/edge-password:ro']}
    },'volumes':{k:{} for k in ['db','mqtt','revision','revision-copy','sim']}}
    config['services']['role-probe']={**config['services']['backend'],
        'environment':{**config['services']['backend']['environment'],
            'PARKING_DB_URL':base_env['PARKING_DB_URL']+'?user=postgres&options=-c%20role%3Dparking_runtime'},
        'volumes':volumes+[str(admin_sec)+':/run/secrets:ro',str(CASE/'ca.crt')+':/tls/ca.crt:ro']}
    (CASE/'compose.json').write_text(json.dumps(config))
    try:
        dc('up','-d','postgres','mosquitto')
        wait_until(lambda:sql('SELECT 1').strip()=='1')
        sql((ROOT/'infra/deploy/sql/create-roles.sql').read_text())
        sql("ALTER DATABASE parking_handoff OWNER TO parking_migrator; ALTER ROLE parking_migrator PASSWORD '"+migrator_password+"'; ALTER ROLE parking_runtime PASSWORD '"+runtime_password+"';")
        print('Building isolated backend and simulator images...',flush=True)
        dc('build','bootstrap','simulator',timeout=900)
        sql('CREATE DATABASE parking_migration_probe OWNER parking_migrator;')
        for _ in range(2):
            assert 'DATABASE_MIGRATED' in dc('run','--rm','migrate',timeout=120)
        try:dc('run','--rm','-e','PARKING_DB_USERNAME=parking_runtime','-v',str(sec)+':/run/secrets:ro','migrate',timeout=120)
        except RuntimeError as error:assert 'migration_account_required' in str(error)
        else:raise AssertionError('runtime must not run migration CLI')
        print('PASS: separate migration process, repeat migration and runtime rejection',flush=True)
        dc('run','--rm','bootstrap',timeout=120)
        sql('SET ROLE parking_migrator;\n'+(ROOT/'infra/deploy/sql/grants.sql').read_text())
        # Successful DML and row-lock ability as runtime; privileges denied before row matching.
        assert sql('SELECT session_user, current_user','parking_runtime').strip()=='parking_runtime|parking_runtime'
        assert 'zone-01' in sql('BEGIN; SELECT zone_id FROM zone_view FOR UPDATE; SELECT device_id FROM device FOR UPDATE; ROLLBACK;','parking_runtime')
        probes=["CREATE TABLE forbidden(id int)","ALTER TABLE event_message ADD COLUMN forbidden text","UPDATE event_message SET payload=payload WHERE false","UPDATE zone_policy SET content=content WHERE false","DELETE FROM zone_policy WHERE false","UPDATE device SET enabled=false WHERE false","UPDATE app_user SET enabled=false WHERE false","DELETE FROM user_zone_access WHERE false","INSERT INTO flyway_schema_history(installed_rank) VALUES (99)","TRUNCATE event_message","SET ROLE parking_migrator"]
        for statement in probes:
            denied_sql(statement,'parking_runtime')
        sql("BEGIN; INSERT INTO app_user(user_id,username,password_hash,role) VALUES ('operator-probe','operator-probe','!disabled','VIEWER'); INSERT INTO user_zone_access(user_id,zone_id) VALUES ('operator-probe','zone-01'); DELETE FROM user_zone_access WHERE user_id='operator-probe'; UPDATE app_user SET enabled=false WHERE user_id='operator-probe'; INSERT INTO audit_entry(audit_id,action,detail,created_at) VALUES ('operator-probe','TEST','{}',now()); ROLLBACK;",'parking_operator')
        for user in ['parking_operator','parking_backup']:
            for statement in ["UPDATE zone_policy SET content=content WHERE false","DELETE FROM event_message WHERE false","CREATE TABLE forbidden(id int)","SET ROLE parking_migrator"]:
                denied_sql(statement,user)
        denied_sql('UPDATE app_user SET enabled=false WHERE false','parking_backup')
        assert 'PostgreSQL database dump' in dc('exec','-T','postgres','pg_dump','-U','parking_backup','-d','parking_handoff')
        print('PASS: runtime row locks and denied DDL/policy/device/account/migration writes',flush=True)
        print('PASS: operator account/grant/audit writes and read-only backup dump',flush=True)
        port=int(dc('port','mosquitto','8883').strip().rsplit(':',1)[1])
        mqtt_client('localhost',port,None,None,expect_failure=True)
        mqtt_client('localhost',port,'edge-01','wrong-password',expect_failure=True)
        mqtt_client('localhost',port,'edge-01',edge_password,ca='wrong-ca.crt',expect_failure=True)
        mqtt_client('127.0.0.1',port,'edge-01',edge_password,expect_failure=True)
        client=mqtt_client('localhost',port,'edge-01',edge_password)
        denied_publish(client,'parking/v2/devices/edge-02/events')
        denied_publish(client,'parking/v2/devices/edge-01/acks');client.disconnect()
        print('PASS: anonymous/password/CA/hostname rejection and topic write isolation',flush=True)
        # Broker ACL must prevent delivery even if a SUBACK itself is granted.
        edge=mqtt_client('localhost',port,'edge-01',edge_password);server=mqtt_client('localhost',port,'parking-server',server_password)
        seen=[];edge.on_message=lambda _c,_u,m:seen.append(m.topic)
        edge.subscribe([('parking/v2/devices/edge-01/acks',1),('parking/v2/devices/edge-02/acks',1)])
        for _ in range(10):edge.loop(.1)
        server.publish('parking/v2/devices/edge-02/acks','denied',qos=1);server.publish('parking/v2/devices/edge-01/acks','allowed',qos=1)
        for _ in range(20):edge.loop(.1);server.loop(.1)
        assert seen==['parking/v2/devices/edge-01/acks'];edge.disconnect();server.disconnect()
        for argument,reason in [('--spring.profiles.active=app-prod,mqtt-ingest-local','production_local_profile_forbidden'),('--spring.flyway.enabled=true','production_runtime_migration_forbidden')]:
            try:dc('run','--rm','backend',argument,timeout=60)
            except RuntimeError as error:
                assert reason in str(error)
                assert 'HikariPool-1 - Starting' not in str(error), 'production guard must run before database connection'
            else:raise AssertionError('unsafe production configuration accepted')
        rejected_backend(['production_secret_file_required:POSTGRES_PASSWORD'],
            '-v',str(empty_sec)+':/run/secrets:ro','-e','POSTGRES_PASSWORD='+runtime_password,
            '-e','PARKING_MQTT_PASSWORD='+server_password,'-e','PARKING_CURSOR_HMAC_KEY='+ (sec/'PARKING_CURSOR_HMAC_KEY').read_text(),before_db=True)
        rejected_backend(['production_database_role_invalid'],service='role-probe')
        rejected_backend([],
            '-v',str(CASE/'wrong-ca.crt')+':/tls/ca.crt:ro',
            broker_markers=('alert certificate unknown','alert unknown ca','alert bad certificate'))
        rejected_backend([],
            '-e','PARKING_MQTT_URL=ssl://wrong-host:8883',
            broker_markers=('alert certificate unknown','alert unknown ca','alert bad certificate'))
        rejected_backend([],
            '-v',str(wrong_sec)+':/run/secrets:ro',broker_markers=('not authorised','not authorized'))
        print('PASS: actual JVM rejects missing secret files, privileged login, wrong CA/hostname/password',flush=True)
        dc('up','-d','backend')
        wait_until(lambda:'EVENT_INGEST_READY' in dc('logs','backend'),seconds=60)
        for host,ca,password in [('wrong-host','/tls/ca.crt','/run/edge-password'),
            ('mosquitto','/tls/wrong-ca.crt','/run/edge-password'),('mosquitto','/tls/ca.crt','/run/wrong-edge-password')]:
            output=dc('run','--rm','-v',str(CASE/'wrong-ca.crt')+':/tls/wrong-ca.crt:ro',
                '-v',str(CASE/'wrong-edge-password')+':/run/wrong-edge-password:ro','simulator',
                '--host',host,'--port','8883','--tls-ca',ca,'--username','edge-01','--password-file',password,
                '--state','/state/rejected.sqlite','--seconds','3',timeout=30)
            assert 'APPLICATION_ACK' not in output
        assert dc('run','--rm','--entrypoint','python','simulator','-c',
            "import sqlite3; print(sqlite3.connect('/state/rejected.sqlite').execute('SELECT count(*) FROM outbox WHERE status=\"PENDING\"').fetchone()[0])").strip()=='3'
        print('PASS: actual simulator rejects wrong CA/hostname/password and retains all pending events',flush=True)
        result=dc('run','--rm','simulator',timeout=60)
        assert '"status": "STORED"' in result, 'Expected post-commit application ACK under TLS/runtime role'
        assert int(sql("SELECT count(*) FROM event_message WHERE message_id LIKE 'demo-%'").strip())>=1
        assert sql("SELECT connection_state FROM zone_view WHERE zone_id='zone-01'").strip()=='ONLINE'
        print('PASS: actual app-prod runtime MQTT event, heartbeat, SQL commit and application ACK',flush=True)
        verify_ingestion(port,edge_password)
        # Age only disposable fixtures. The actual runtime scheduled purge must have DELETE/INSERT rights.
        sql("UPDATE event_message SET received_at=now()-interval '31 days'; UPDATE incident SET state='CLOSED_EXIT', close_reason='OBSERVED_EXIT', started_at=now()-interval '32 days', closed_at=now()-interval '31 days', first_close_received_at=now()-interval '31 days', summary_expires_at=now()-interval '1 day';")
        wait_until(lambda:sql('SELECT count(*) FROM event_message').strip()=='0' and sql('SELECT count(*) FROM incident').strip()=='0',seconds=80)
        assert int(sql('SELECT count(*) FROM entity_tombstone').strip())>=1
        assert int(sql("SELECT count(*) FROM inbound_receipt WHERE outcome='STORED'").strip())>=1
        print('PASS: runtime retention purge, tombstone and durable duplicate receipt',flush=True)
        print('BACKEND_TEST_IMAGE '+run('docker','image','inspect','--format','{{.Id}}',image).strip(),flush=True)
        print('O1_ACCEPTANCE_PASSED',flush=True)
    except Exception:
        try:print(redact(dc('logs','--tail','80','backend','mosquitto')),file=sys.stderr)
        except RuntimeError:pass
        raise
    finally:
        dc('down','--volumes','--remove-orphans',timeout=90)
        shutil.rmtree(CASE)

if __name__=='__main__':main()
