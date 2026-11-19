"""Render reviewable server files only. Never creates accounts, buckets, DNS or containers."""
import argparse
import copy
import json
from pathlib import Path
import re
import shutil
import uuid

ROOT=Path(__file__).resolve().parents[2]
IMAGE_NAMES=('backend','web','postgres','mosquitto','ledger','prometheus','grafana','node-exporter','blackbox')


def example_input():
    return {'domain':'illegal-parking-detection.duckdns.org','state_dir':'/opt/parking/state',
            'public_access':False,'devices':['edge-01'],'region':'ap-northeast-2','backup_owner':'123456789012',
            'ledger_bucket':'replace-with-ledger-bucket','backup_bucket':'replace-with-backup-bucket',
            'store_id':'fd09530c-042e-4368-8fd4-c5ec2bbdd490',
            'ledger_principal':'arn:aws:iam::111122223333:role/parking-ledger',
            'backup_principal':'arn:aws:iam::111122223333:role/parking-backup',
            'images':{name:f'example.invalid/parking/{name}@sha256:'+('0'*64) for name in IMAGE_NAMES}}


def validate(cfg):
    if set(cfg)!=set(example_input()): raise ValueError('explicit_server_inputs_required')
    if not re.fullmatch(r'(?=.{1,253}$)(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]{2,63}',cfg['domain']): raise ValueError('invalid_domain')
    state=Path(cfg['state_dir'])
    if not re.fullmatch(r'/(?:[A-Za-z0-9_-]+/)+[A-Za-z0-9_-]+',str(state)) or '..' in state.parts: raise ValueError('dedicated_absolute_state_path_required')
    if type(cfg['public_access']) is not bool: raise ValueError('explicit_public_access_boolean_required')
    devices=cfg['devices']
    if not isinstance(devices,list) or not devices or len(devices)!=len(set(devices)) or any(not re.fullmatch('[A-Za-z0-9_-]{1,64}',d) or d=='parking-server' for d in devices): raise ValueError('invalid_device_ids')
    if set(cfg['images'])!=set(IMAGE_NAMES) or any(not re.fullmatch(r'[a-zA-Z0-9][a-zA-Z0-9._:/-]+@sha256:[a-f0-9]{64}',v) for v in cfg['images'].values()): raise ValueError('registry_digest_required')
    import sys
    sys.path.insert(0,str(ROOT/'infra/external-storage'))
    from s3_store import validate_config
    for name in ('ledger','backup'):
        validate_config({'bucket':cfg[name+'_bucket'],'owner':cfg['backup_owner'],'region':cfg['region'],'prefix':name,'store_id':cfg['store_id']})
        if not re.fullmatch(r'arn:aws:iam::[0-9]{12}:(role|user)/[A-Za-z0-9_+=,.@/-]+',cfg[name+'_principal']): raise ValueError('explicit_principal_required')
    if cfg['ledger_bucket']==cfg['backup_bucket'] or cfg['ledger_principal']==cfg['backup_principal']: raise ValueError('separate_storage_roles_and_buckets_required')


def policies(cfg,kind):
    bucket='arn:aws:s3:::'+cfg[kind+'_bucket']; principal=cfg[kind+'_principal']
    resource=bucket+('/revision/head.json' if kind=='ledger' else '/backups/*')
    conditional='s3:if-match' if kind=='ledger' else 's3:if-none-match'
    allowed=[{'Effect':'Allow','Action':['s3:GetBucketVersioning'],'Resource':[bucket]},
             {'Effect':'Allow','Action':['s3:GetObject']+([] if kind=='ledger' else ['s3:GetObjectVersion']),'Resource':[resource]},
             {'Effect':'Allow','Action':['s3:PutObject'],'Resource':[resource],'Condition':{'Null':{conditional:'false'}}}]
    deny=[{'Sid':'DenyInsecureTransport','Effect':'Deny','Principal':'*','Action':'s3:*','Resource':[bucket,bucket+'/*'],'Condition':{'Bool':{'aws:SecureTransport':'false'}}},
          {'Sid':'DenyRuntimeMutationAndDeletion','Effect':'Deny','Principal':{'AWS':principal},
           'Action':['s3:DeleteObject','s3:DeleteObjectVersion','s3:DeleteBucket','s3:PutBucket*','s3:DeleteBucket*','s3:PutLifecycleConfiguration','s3:PutObjectAcl','s3:PutObjectRetention','s3:PutObjectLegalHold','s3:BypassGovernanceRetention'],
           'Resource':[bucket,bucket+'/*']},
          {'Sid':'RequireConditionalWrite','Effect':'Deny','Principal':{'AWS':principal},'Action':'s3:PutObject','Resource':[resource],'Condition':{'Null':{conditional:'true'}}}]
    if kind=='ledger':
        deny.append({'Sid':'DenyRuntimeHistoricalReads','Effect':'Deny','Principal':{'AWS':principal},'Action':['s3:GetObjectVersion'],'Resource':[bucket+'/*']})
    resource_allow=[dict(s,Principal={'AWS':principal}) for s in allowed]
    return {'Version':'2012-10-17','Statement':allowed}, {'Version':'2012-10-17','Statement':resource_allow+deny}


def render(cfg):
    validate(cfg); root=cfg['state_dir']; domain=cfg['domain']; bind='0.0.0.0' if cfg['public_access'] else '127.0.0.1'
    secret_names=('admin-password','migrator-password','runtime-password','operator-password','backup-password','mqtt-server-password','cursor-key','monitoring-password','grafana-password','revision-ledger-token','aws-ledger-credentials','web-password')
    def service(image,networks,memory):
        return {'image':cfg['images'][image],'platform':'linux/amd64','pull_policy':'if_not_present',
                'networks':networks,'mem_limit':memory,'memswap_limit':memory,'security_opt':['no-new-privileges:true'],
                'logging':{'driver':'json-file','options':{'max-size':'10m','max-file':'3'}}}
    services={}
    services['postgres']=dict(service('postgres',['data'],'512m'),environment={'POSTGRES_DB':'parking_handoff','POSTGRES_PASSWORD_FILE':'/run/secrets/admin-password'},
        secrets=list(secret_names[:5]),volumes=['db:/var/lib/postgresql/data','./init-db.sql:/docker-entrypoint-initdb.d/10-parking.sql:ro','./create-roles.sql:/opt/parking/create-roles.sql:ro'],
        healthcheck={'test':['CMD-SHELL','pg_isready -U postgres -d parking_handoff'],'interval':'5s','timeout':'3s','retries':30})
    services['mosquitto']=dict(service('mosquitto',{'mqtt':{'aliases':[domain]},'ingress':{}},'64m'),
        ports=[f'{bind}:8883:8883'],volumes=[root+'/broker:/mosquitto/config:ro','mqtt:/mosquitto/data'])
    ledger_env={'PARKING_REVISION_LEDGER_REQUIRED':'true','PARKING_REVISION_LEDGER_SCOPE':'AWS_S3','PARKING_REVISION_LEDGER_URL':'http://revision-ledger:8088',
        'PARKING_REVISION_LEDGER_ID':cfg['store_id'],'PARKING_REVISION_LEDGER_TOKEN_FILE':'/run/secrets/revision-ledger-token'}
    db_env={'PARKING_DB_URL':'jdbc:postgresql://postgres:5432/parking_handoff','SPRING_CONFIG_IMPORT':'configtree:/run/secrets/'}
    revision_env={'PARKING_REVISION_PRIMARY':'/state/revisions.json','PARKING_REVISION_BACKUP':'/state-copy/revisions.json'}
    services['revision-ledger']=dict(service('ledger',['ledger','egress'],'128m'),user='10001:10001',read_only=True,cap_drop=['ALL'],tmpfs=['/tmp'],
        command=['serve','--config','/config/ledger-s3.json','--credentials-file','/run/secrets/aws-ledger-credentials','--token-file','/run/secrets/revision-ledger-token'],
        secrets=['aws-ledger-credentials','revision-ledger-token'],volumes=['./ledger-s3.json:/config/ledger-s3.json:ro'])
    services['backend']=dict(service('backend',['app','data','mqtt','ledger','monitoring'],'1024m'),
        environment={**db_env,**revision_env,**ledger_env,'SPRING_PROFILES_ACTIVE':'app-prod,monitoring','SERVER_SERVLET_SESSION_COOKIE_NAME':'PARKING_SESSION',
                     'PARKING_MQTT_URL':f'ssl://{domain}:8883','PARKING_MQTT_CA_FILE':'/tls/ca.crt','PARKING_BACKUP_STATUS_FILE':'/backup-status/last-success.json'},
        secrets=[{'source':'runtime-password','target':'POSTGRES_PASSWORD'},{'source':'mqtt-server-password','target':'parking.mqtt.password'},
                 {'source':'cursor-key','target':'PARKING_CURSOR_HMAC_KEY'},{'source':'monitoring-password','target':'parking.monitoring.password'},'revision-ledger-token'],
        volumes=['revision:/state','revision-copy:/state-copy',root+'/tls/ca.crt:/tls/ca.crt:ro',root+'/backup-status:/backup-status:ro'],
        read_only=True,tmpfs=['/tmp'],depends_on={'postgres':{'condition':'service_healthy'},'revision-ledger':{'condition':'service_started'},'mosquitto':{'condition':'service_started'}})
    services['web']=dict(service('web',['app','ingress'],'64m'),environment={'PUBLIC_HTTPS_ORIGIN':'https://'+domain,'NGINX_ENVSUBST_FILTER':'^PUBLIC_HTTPS_ORIGIN$'},
        ports=[f'{bind}:80:80',f'{bind}:443:443'],volumes=['./nginx.conf.template:/etc/nginx/templates/default.conf.template:ro',root+'/web-tls:/etc/nginx/tls:ro',root+'/acme:/var/www/acme:ro'],
        read_only=True,tmpfs=['/etc/nginx/conf.d','/var/cache/nginx','/var/run'],depends_on=['backend'])
    for name,main in [('migrate','MigrateDatabaseCliKt'),('recovery','RecoveryCliKt'),('initialize-virtual','PrepareHandoffCliKt')]:
        services[name]=dict(service('backend',['data','ledger'],'384m'),profiles=['tools'],
            entrypoint=['java','-Xmx256m','-Dloader.main=kr.ac.sejong.parkingtools.'+main,'-cp','/app/app.jar','org.springframework.boot.loader.launch.PropertiesLauncher'],
            environment={**db_env,**revision_env,**ledger_env,'PARKING_DB_USERNAME':'parking_migrator'},
            secrets=[{'source':'migrator-password','target':'POSTGRES_PASSWORD'},'revision-ledger-token'],
            volumes=['revision:/state','revision-copy:/state-copy',root+'/recovery:/recovery'],read_only=True,tmpfs=['/tmp'],cap_drop=['ALL'])
    services['migrate']['command']=['--migrate']
    services['initialize-virtual']['command']=[]
    services['initialize-virtual']['user']='0:0'
    services['initialize-virtual']['cap_add']=['CHOWN','DAC_OVERRIDE']
    services['initialize-virtual']['entrypoint']=['/bin/sh','/ops/initialize-virtual.sh']
    services['initialize-virtual']['volumes'].append('./initialize-virtual.sh:/ops/initialize-virtual.sh:ro')
    services['operator']=dict(service('backend',['data'],'384m'),profiles=['tools'],entrypoint=['/bin/sh','/ops/operator.sh'],
        environment={**db_env,'PARKING_DB_USERNAME':'parking_operator'},secrets=[{'source':'operator-password','target':'POSTGRES_PASSWORD'},'web-password'],
        volumes=['./operator.sh:/ops/operator.sh:ro'],read_only=True,tmpfs=['/tmp'])
    services['grants']=dict(service('postgres',['data'],'64m'),profiles=['tools'],secrets=['migrator-password'],volumes=['./grants.sql:/opt/parking/grants.sql:ro'],
        entrypoint=['/bin/sh','-c','export PGPASSWORD="$$(cat /run/secrets/migrator-password)"; exec psql -X -v ON_ERROR_STOP=1 -h postgres -U parking_migrator -d parking_handoff -f /opt/parking/grants.sql'])
    services['prometheus']=dict(service('prometheus',['monitoring'],'384m'),
        command=['--config.file=/etc/prometheus/prometheus.yml','--storage.tsdb.path=/prometheus','--storage.tsdb.retention.time=7d','--storage.tsdb.retention.size=1GB'],
        secrets=['monitoring-password'],volumes=['./prometheus:/etc/prometheus:ro','prometheus:/prometheus'],read_only=True,tmpfs=['/tmp'],cap_drop=['ALL'])
    services['grafana']=dict(service('grafana',['monitoring'],'512m'),ports=['127.0.0.1:3000:3000'],
        environment={'GF_SECURITY_ADMIN_USER':'monitoring-admin','GF_SECURITY_ADMIN_PASSWORD__FILE':'/run/secrets/grafana-password','GF_AUTH_ANONYMOUS_ENABLED':'false','GF_USERS_ALLOW_SIGN_UP':'false',
                     'GF_PATHS_PLUGINS':'/opt/parking-plugins','GF_PLUGINS_PREINSTALL_AUTO_UPDATE':'false','GF_PLUGINS_PLUGIN_ADMIN_ENABLED':'false','GF_ANALYTICS_REPORTING_ENABLED':'false','GF_ANALYTICS_CHECK_FOR_UPDATES':'false','GF_PLUGINS_PREINSTALL_DISABLED':'true','GF_PLUGINS_PUBLIC_KEY_RETRIEVAL_DISABLED':'true'},
        secrets=['grafana-password'],volumes=['./grafana/provisioning:/etc/grafana/provisioning:ro','./grafana/dashboards:/etc/grafana/dashboards:ro','grafana:/var/lib/grafana'],read_only=True,tmpfs=['/tmp'],cap_drop=['ALL'])
    services['node-exporter']=dict(service('node-exporter',['monitoring'],'64m'),read_only=True,cap_drop=['ALL'])
    services['blackbox']=dict(service('blackbox',['monitoring','app'],'64m'),command=['--config.file=/etc/blackbox/config.json'],
        volumes=['./blackbox.json:/etc/blackbox/config.json:ro',root+'/tls/ca.crt:/tls/ca.crt:ro'],read_only=True,cap_drop=['ALL'])
    compose={'name':'parking-server','services':services,'networks':{n:({'internal':True} if n not in ('egress','ingress') else {}) for n in ('app','data','mqtt','ledger','monitoring','ingress','egress')},
        'volumes':{n:{} for n in ('db','mqtt','revision','revision-copy','prometheus','grafana')},'secrets':{n:{'file':root+'/secrets/'+n} for n in secret_names}}
    acl='user parking-server\n'+''.join(f'topic read parking/v2/devices/+/{topic}\n' for topic in ('events','heartbeat','availability'))+'topic write parking/v2/devices/+/acks\n'
    for device in cfg['devices']:
        acl+='\nuser '+device+'\n'+''.join(f'topic write parking/v2/devices/{device}/{topic}\n' for topic in ('events','heartbeat','availability'))+f'topic read parking/v2/devices/{device}/acks\n'
    bundle={'compose.json':compose,'mosquitto.acl':acl,'blackbox.json':{'modules':{'parking_https':{'prober':'http','timeout':'4s','http':{'valid_status_codes':[200],'follow_redirects':False,'tls_config':{'ca_file':'/tls/ca.crt','server_name':domain,'insecure_skip_verify':False}}}}}}
    for kind,prefix in [('ledger','revision'),('backup','backups')]:
        bundle[kind+'-s3.json']={'bucket':cfg[kind+'_bucket'],'owner':cfg['backup_owner'],'region':cfg['region'],'prefix':prefix,'store_id':cfg['store_id']}
        bundle[kind+'-runtime-policy.json'],bundle[kind+'-bucket-policy.json']=policies(cfg,kind)
    return bundle


def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--input',type=Path,required=True);parser.add_argument('--output',type=Path,required=True);args=parser.parse_args()
    cfg=json.loads(args.input.read_text());bundle=render(cfg)
    args.output.mkdir(mode=0o700)  # Never replace an existing reviewed release.
    for name,value in bundle.items(): (args.output/name).write_text((json.dumps(value,ensure_ascii=False,indent=2)+'\n') if isinstance(value,dict) else value)
    copies={'init-db.sql':'infra/deploy/init-db.sql','create-roles.sql':'infra/deploy/sql/create-roles.sql','grants.sql':'infra/deploy/sql/grants.sql','operator.sh':'infra/deploy/operator.sh','nginx.conf.template':'infra/server/nginx.conf.template','mosquitto.conf':'infra/deploy/mosquitto/mosquitto.conf','initialize-virtual.sh':'infra/server/initialize-virtual.sh'}
    for target,source in copies.items(): shutil.copyfile(ROOT/source,args.output/target)
    shutil.copytree(ROOT/'infra/monitoring/prometheus',args.output/'prometheus')
    shutil.copytree(ROOT/'infra/monitoring/grafana',args.output/'grafana')
    shutil.copyfile(ROOT/'infra/server/external-backup-alerts.yml',args.output/'prometheus/external-backup-alerts.yml')
    prom=args.output/'prometheus/prometheus.yml'
    prom.write_text(prom.read_text().replace('rule_files: [/etc/prometheus/alerts.yml]', 'rule_files: [/etc/prometheus/alerts.yml, /etc/prometheus/external-backup-alerts.yml]'))
    print('SERVER_FILES_RENDERED_NOT_DEPLOYED')


if __name__=='__main__':main()
