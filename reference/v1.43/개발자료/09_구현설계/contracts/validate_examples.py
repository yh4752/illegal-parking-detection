"""Contract validation only. Does not start a server, broker, or edge board.

python -m pip install -r requirements.txt
python validate_examples.py --contract /path/to/04_...json
"""
from pathlib import Path
import argparse,copy,datetime as dt,json,math,sys,importlib.metadata
HERE=Path(__file__).resolve().parent
if (HERE/'.validation-deps').is_dir():sys.path.insert(0,str(HERE/'.validation-deps'))
from jsonschema import Draft202012Validator,FormatChecker
from openapi_spec_validator import OpenAPIV31SpecValidator

def parse_strict(text):
    def pairs(items):
        d={}
        for k,v in items:
            if k in d:raise ValueError('duplicate JSON key: '+k)
            d[k]=v
        return d
    def bad(v):raise ValueError('nonfinite JSON constant: '+v)
    if isinstance(text,bytes):text=text.decode('utf-8',errors='strict')
    return json.loads(text,object_pairs_hook=pairs,parse_constant=bad)

def semantic_errors(value):
    errors=[]
    def utc(x):return dt.datetime.fromisoformat(x.replace('Z','+00:00'))
    def walk(x,path='$'):
        if isinstance(x,float) and not math.isfinite(x):errors.append(path+' nonfinite');return
        if isinstance(x,list):
            for i,v in enumerate(x):walk(v,f'{path}[{i}]')
        if not isinstance(x,dict):return
        if {'schema_version','snapshot','device_seq'}<=x.keys():
            for k in ['device_seq','boot_id','clock_synced']:
                if x[k]!=x['snapshot'][k]:errors.append(path+' envelope snapshot '+k)
        if 'event_type' in x:
            if x.get('event') is not None and x['event_id']!=x['event']['event_id']:errors.append(path+' event identity')
            if x.get('image_update') is not None and x['event_id']!=x['image_update']['event_id']:errors.append(path+' image event identity')
        if {'command_id','issued_at','expires_at','params','type'}<=x.keys():
            if (utc(x['expires_at'])-utc(x['issued_at'])).total_seconds()!=60:errors.append(path+' command ttl')
            if x['type']=='STOP_GUIDANCE':
                start=utc(x['params']['starts_at']);issued=utc(x['issued_at'])
                if start<issued:errors.append(path+' stop starts before issued')
                if start>issued and x['scope']!='ZONE':errors.append(path+' future STOP scope')
                if x['params']['expires_at'] is not None and utc(x['params']['expires_at'])<=start:errors.append(path+' pause expiry')
        if {'first_guidance_ms','unresolved_ms','max_attempts'}<=x.keys():
            if x['unresolved_ms']<=x['first_guidance_ms']:errors.append(path+' unresolved must exceed first guidance')
        if {'weekday','start_hhmm','end_hhmm'}<=x.keys():
            if x['start_hhmm']>=x['end_hhmm']:errors.append(path+' operating window must not wrap midnight')
        if {'event','id','data'}<=x.keys() and x['event']=='zone_snapshot':
            if x['id']!=str(x['data']['view_revision']):errors.append(path+' SSE id/revision')
        for k,v in x.items():walk(v,path+'.'+k)
    walk(value)
    return errors

def clock_decision(external_ok,same_boot,age_ms,error_ms,probe_failure_ms=0,jump_ms=0):
    """Synthetic boundary oracle only; no daemon/board adapter implementation."""
    if not same_boot or error_ms is None or age_ms is None or error_ms>1000 or age_ms>1800000 or probe_failure_ms>=10000 or abs(jump_ms)>500:return 'UNTRUSTED'
    if external_ok and age_ms<=300000:return 'SYNCED'
    return 'HOLDOVER'

def main():
    ap=argparse.ArgumentParser();ap.add_argument('--contract',type=Path,default=HERE.parents[1]/'04_공통_통신계약과_메시지예제_v1.4.json');ap.add_argument('--report',type=Path,default=HERE/'validation_report.json');args=ap.parse_args()
    c=parse_strict(args.contract.read_bytes());s=parse_strict((HERE/'parking.schema.json').read_bytes());o=parse_strict((HERE/'openapi.json').read_bytes());e=c['examples']
    Draft202012Validator.check_schema(s)
    openapi_errors=list(OpenAPIV31SpecValidator(o).iter_errors());assert not openapi_errors,[str(x) for x in openapi_errors]
    def validator(name):return Draft202012Validator({'$schema':s['$schema'],'$ref':'#/$defs/'+name,'$defs':s['$defs']},format_checker=FormatChecker())
    def check(name,v,semantics=True):
        errs=[x.message for x in validator(name).iter_errors(v)]
        if not errs and semantics:errs+=semantic_errors(v)
        return errs
    mapping={'guidance_complete':'EventMessage','heartbeat':'Heartbeat','ack_stored':'Ack','ack_duplicate':'Ack','stop_command':'Command','stop_applied':'CommandResult','zone_view':'ZoneView','config_desired':'ConfigDesired','availability_online':'Availability','config_reported':'ConfigReported','snapshot_request':'Command','image_metadata':'ImageMetadata','snapshot_ready':'CommandResult','resume_command':'Command','sse_zone_snapshot':'SseEvent','rest_error':'RestError','manual_close_event':'EventMessage','automatic_photo_available':'EventMessage','image_ref_available':'ImageRef','image_ref_failed':'ImageRef','clock_status_holdover':'ClockStatus','clock_status_untrusted':'ClockStatus','clock_policy_boundaries':'ClockBoundaryExamples','image_metadata_close_confirm':'ImageMetadata','image_metadata_close_reuse':'ImageMetadata'}
    assert set(mapping)==set(e),'Every canonical example must have explicit schema coverage'
    positives=[]
    for key,name in mapping.items():
        errs=check(name,e[key]);assert not errs,(key,errs)
        positives.append({'example':key,'schema':name,'status':'PASS'})
    assert len(e)==25
    for name,shape in c['objects'].items():assert set(shape)==set(s['$defs'][name]['properties']),(name,'field coverage')
    for name,values in c['enums'].items():assert s['$defs'][name]['enum']==values,(name,'enum coverage')
    assert set(s['x-topic-definitions'])==set(c['message_families'])
    union=Draft202012Validator(s,format_checker=FormatChecker())
    mqtt_examples=[k for k,n in mapping.items() if n in set(s['x-topic-definitions'].values())]
    for k in mqtt_examples:assert not list(union.iter_errors(e[k])),k
    negatives=[]
    def negative(label,name,original,path,new='__DELETE__',sem=True):
        v=copy.deepcopy(original);target=v
        for k in path[:-1]:target=target[k]
        if new=='__DELETE__':del target[path[-1]]
        else:target[path[-1]]=new
        errs=check(name,v,sem);assert errs,(label,'negative unexpectedly accepted');negatives.append({'case':label,'status':'REJECTED','first_error':errs[0]})
    base=e['heartbeat']
    negative('missing required device_id','Heartbeat',base,['device_id'])
    negative('unknown root property','Heartbeat',base,['extra'],True)
    negative('unknown nested snapshot property','Heartbeat',base,['snapshot','extra'],True)
    negative('unsupported schema version','Heartbeat',base,['schema_version'],1)
    negative('boolean is not integer sequence','Heartbeat',base,['device_seq'],True)
    negative('integer exceeds JS safe range','Heartbeat',base,['device_seq'],9007199254740992)
    negative('empty identifier','Heartbeat',base,['message_id'],'')
    negative('unknown incident enum','Heartbeat',base,['snapshot','incident_state'],'PARKED')
    negative('missing required nullable field','Heartbeat',base,['snapshot','observed_at'])
    negative('false and zero are preserved not null','Heartbeat',base,['storage_ok'],None)
    negative('bad UTC offset format','Heartbeat',base,['occurred_at'],'2026-11-03T12:00:00+09:00')
    negative('impossible calendar date','Heartbeat',base,['occurred_at'],'2026-02-30T00:00:00Z')
    negative('negative fps','Heartbeat',base,['fps'],-1)
    negative('sequence mismatch','Heartbeat',base,['snapshot','device_seq'],999)
    negative('boot mismatch','Heartbeat',base,['snapshot','boot_id'],'other')
    negative('clock flag mismatch','Heartbeat',base,['snapshot','clock_synced'],False)
    negative('eventless stale event fields','Heartbeat',base,['snapshot','current_event_id'],None)
    negative('cleared pause cannot remain active snapshot','ZoneView',e['zone_view'],['snapshot','pauses',0,'status'],'CLEARED')
    negative('more than two attempts','Heartbeat',base,['snapshot','guidance_count'],3)
    negative('untrusted nonnull observation timestamp','Heartbeat',base,['snapshot','clock_status','state'],'UNTRUSTED')
    negative('invalid trusted clock bound','ClockStatus',e['clock_status_holdover'],['error_bound_ms'],1001)
    negative('holdover exceeds age','ClockStatus',e['clock_status_holdover'],['sync_age_ms'],1800001)
    negative('photo update missing body','EventMessage',e['automatic_photo_available'],['image_update'],None)
    negative('normal event has image update','EventMessage',e['guidance_complete'],['image_update'],e['automatic_photo_available']['image_update'])
    negative('failed photo missing error','ImageRef',e['image_ref_failed'],['error_code'],None)
    negative('available photo missing retention','ImageRef',e['image_ref_available'],['expires_at'],None)
    negative('invalid JPEG hash','ImageMetadata',e['image_metadata'],['sha256'],'X'*64)
    negative('image dimensions exceed profile','ImageMetadata',e['image_metadata'],['width'],1281)
    negative('reuse on wrong kind','ImageMetadata',e['image_metadata'],['source_image_id'],'image-old')
    negative('public view token leak','ZoneView',e['zone_view'],['commands',0,'result_data','confirmation_token'],'secret')
    negative('wrong command parameters','Command',e['resume_command'],['params'],{'purpose':'VIEW'})
    negative('wrong command scope','Command',e['resume_command'],['scope'],'ZONE')
    negative('resume lacks token','Command',e['resume_command'],['confirmation_token'],None)
    negative('invalid command TTL','Command',e['stop_command'],['expires_at'],'2026-11-03T03:06:06Z')
    negative('future STOP outside ZONE','Command',e['stop_command'],['params','starts_at'],'2026-11-03T03:06:00Z')
    negative('wire STOP cannot carry web null','Command',e['stop_command'],['params','starts_at'],None)
    negative('first guidance beyond unresolved','Policy',e['config_desired']['desired_policy'],['timers','unresolved_ms'],300000)
    negative('SSE revision mismatch','SseEvent',e['sse_zone_snapshot'],['id'],'9')
    for text in ['{"x":1,"x":2}','{"fps":NaN}','{"fps":Infinity}',b'\xff']:
        try:parse_strict(text)
        except (ValueError,UnicodeError):negatives.append({'case':'strict JSON parser '+repr(text),'status':'REJECTED'})
        else:raise AssertionError('strict JSON parser accepted '+repr(text))
    assert len(json.dumps(e['heartbeat'],ensure_ascii=False).encode())<=c['limits']['metadata_message_max_bytes']
    oversized=copy.deepcopy(e['heartbeat']);oversized['message_id']='x'*70000
    assert len(json.dumps(oversized).encode())>c['limits']['metadata_message_max_bytes']
    negatives.append({'case':'serialized metadata size >64KiB','status':'REJECTED before JSON schema'})

    # Every HTTP operation receives a fixture; binary/SSE transport itself remains an integration check.
    policy=copy.deepcopy(e['config_desired']['desired_policy']);policy_input={k:v for k,v in policy.items() if k not in ['version','base_version','change_class']}
    cmd={k:copy.deepcopy(v) for k,v in e['stop_command'].items() if k not in ['schema_version','command_id','device_id','issued_at','expires_at']};cmd['client_request_id']='req-test-01';cmd['params'].pop('pause_id');cmd['params']['starts_at']=None
    auth={'user_id':'operator-01','role':'OPERATOR'};review={'review_id':'review-01','event_id':'evt-001','created_by':'operator-01','created_at':'2026-11-03T03:07:00Z','note':'checked','use_type':None}
    detail={'event':e['guidance_complete']['event'],'manual_use_type':None,'last_device_seq':20,'messages':[e['guidance_complete']],'reviews':[review],'images':[e['image_ref_available']]}
    requests={'op02':('LoginRequest',{'username':'example','password':'EXAMPLE_NOT_A_SECRET'}),'op09':('PolicyPutRequest',{'expected_version':2,'policy':policy_input,'reason':'initial test'}),'op10':('CommandCreateRequest',cmd),'op12':('ReviewCreateRequest',{'client_request_id':'review-req-01','note':'checked','use_type':None}),'op13':('ImageMetadata',e['image_metadata'])}
    responses={'op01':('CsrfResponse',{'token':'EXAMPLE_CSRF'}),'op02':('AuthUser',auth),'op04':('AuthUser',auth),'op05':('ZoneView',e['zone_view']),'op06':('EventListResponse',{'items':[{**e['guidance_complete']['event'],'manual_use_type':None}],'next_cursor':None}),'op07':('EventDetail',detail),'op08':('PolicyGetResponse',{'latest_policy':policy,'device_versions':{'received_policy_version':3,'active_policy_version':3,'event_policy_version':3}}),'op09':('PolicyPutResponse',{'version':4,'delivery_status':'QUEUED'}),'op10':('CommandCreateResponse',{'command_id':'cmd-001','status':'QUEUED','expires_at':'2026-11-03T03:06:05Z'}),'op11':('AuthorizedCommandView',e['zone_view']['commands'][0]),'op12':('ReviewCreateResponse',{'review_id':'review-01','manual_use_type':None}),'op13':('ImageUploadResponse',{'image_id':'image-001','stored_at':'2026-11-03T03:05:10.200Z'})}
    apifixtures=[]
    operation_set=set()
    for path,methods in o['paths'].items():
        for method,op in methods.items():
            ident=op['operationId'];operation_set.add((method.upper(),path));row={'operation':ident,'method':method.upper(),'path':path,'status':'PASS'}
            if ident in requests:
                n,v=requests[ident];errs=check(n,v);assert not errs,(ident,'request',errs);row['request_schema']=n
            if ident in responses:
                n,v=responses[ident];errs=check(n,v);assert not errs,(ident,'response',errs);row['response_schema']=n
            if ident=='op03':assert 'content' not in op['responses']['204'];row['scope']='204 has no content'
            if ident=='op14':assert 'image/jpeg' in op['responses']['200']['content'];row['scope']='binary response descriptor, no live JPEG transfer'
            if ident=='op15':assert 'text/event-stream' in op['responses']['200']['content'];assert not check('SseEvent',e['sse_zone_snapshot']);row['scope']='SSE data schema; framing/network not exercised'
            apifixtures.append(row)
    assert operation_set=={(a['method'],a['path']) for a in c['api']}
    for n,v in requests.values():
        bad=copy.deepcopy(v);bad['unexpected_field']=1;assert check(n,bad)
    assert not check('EventQuery',{'zone_id':'zone-01','limit':20})
    assert check('EventQuery',{'zone_id':'zone-01','limit':101})
    negative('web STOP server pause id rejected','CommandCreateRequest',cmd,['params','pause_id'],'server-owned')
    negative('web non-STOP null scope rejected','CommandCreateRequest',cmd,['scope'],None)
    assert not check('CommandCreateRequest',cmd)
    scheduled=copy.deepcopy(cmd);scheduled['scope']='ZONE';scheduled['target_event_id']=None;scheduled['params']['starts_at']='2026-11-03T04:00:00Z';assert not check('CommandCreateRequest',scheduled)
    malformed_schedule=copy.deepcopy(scheduled);malformed_schedule['scope']='DEVICE';assert check('CommandCreateRequest',malformed_schedule)
    # Server normalizer reference fixture, not a functioning command endpoint.
    issued='2026-11-03T03:05:05Z';normalized=copy.deepcopy(e['stop_command']);normalized['params']['starts_at']=issued if cmd['params']['starts_at'] is None else cmd['params']['starts_at'];assert not check('Command',normalized)
    assert normalized['params']['starts_at']==normalized['issued_at']
    clock_cases=[(dict(external_ok=True,same_boot=True,age_ms=300000,error_ms=1000),'SYNCED'),(dict(external_ok=True,same_boot=True,age_ms=300001,error_ms=1000),'HOLDOVER'),(dict(external_ok=False,same_boot=True,age_ms=1800000,error_ms=1000),'HOLDOVER'),(dict(external_ok=False,same_boot=True,age_ms=1800001,error_ms=1000),'UNTRUSTED'),(dict(external_ok=True,same_boot=True,age_ms=1,error_ms=1000,probe_failure_ms=10000),'UNTRUSTED'),(dict(external_ok=True,same_boot=True,age_ms=1,error_ms=1000,jump_ms=501),'UNTRUSTED'),(dict(external_ok=False,same_boot=True,age_ms=600000,error_ms=700),'HOLDOVER')]
    for data,expected in clock_cases:assert clock_decision(**data)==expected
    for k in ['captured_at','sha256','boot_id','mode_revision','width','height']:assert e['image_metadata_close_reuse'][k]==e['image_metadata_close_confirm'][k]
    report={'status':'PASS','artifact_revision':'v1.4 detail 2026-11-05','schema_dialect':'2020-12','openapi':'3.1.0','validation_versions':{n:importlib.metadata.version(n) for n in ['jsonschema','openapi-spec-validator','referencing']},'canonical_objects':len(c['objects']),'mqtt_families':len(c['message_families']),'canonical_examples':positives,'negative_cases':negatives,'api_operations':apifixtures,'synthetic_clock_assertions':len(clock_cases),'limitations':['Schema/API specification and example checks only; no application, MQTT broker, database, edge SDK, physical device, or performance integration test was executed.','clock_policy_boundaries is a documented boundary table, not a MQTT message.','Stateful checks in semantic_checks.json are still implementation requirements.','OpenAPI response union permits a live token only by schema; server must enforce OPERATOR identity and token expiry.']}
    args.report.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n');print(json.dumps({'status':'PASS','examples':len(positives),'negative':len(negatives),'api_operations':len(apifixtures),'clock_assertions':len(clock_cases),'report':str(args.report)},ensure_ascii=False))

if __name__=='__main__':main()
