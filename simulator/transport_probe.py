"""CI probe over real MQTT. Test expectations arrive on stdin; no mock ACKs."""
import json,sys,time,uuid
import paho.mqtt.client as mqtt
from device import VALIDATORS
request=json.load(sys.stdin);acks={};ready=False
client=mqtt.Client(mqtt.CallbackAPIVersion.VERSION2,client_id='probe-'+uuid.uuid4().hex[:12],protocol=mqtt.MQTTv5)
def connected(client,_u,_f,reason,_p):
    if not reason.is_failure:client.subscribe('parking/v2/devices/edge-01/acks',qos=1)
def subscribed(_c,_u,_mid,reasons,_p):
    global ready
    ready=not any(r.is_failure for r in reasons)
def message(_c,_u,m):
    if m.retain:return
    ack=json.loads(m.payload);VALIDATORS['Ack'].validate(ack)
    if ack['device_id']=='edge-01':acks[ack['message_id']]=ack
client.on_connect=connected;client.on_subscribe=subscribed;client.on_message=message
client.connect('mosquitto',1883,keepalive=15,clean_start=True)
end=time.monotonic()+10
while not ready and time.monotonic()<end:client.loop(timeout=.1)
assert ready,'ACK subscription failed'
results=[]
try:
    for case in request['messages']:
        payload=case['payload'];message_id=payload['message_id'];acks.pop(message_id,None)
        client.publish('parking/v2/devices/edge-01/events',json.dumps(payload),qos=1,retain=False)
        end=time.monotonic()+case.get('timeout',15)
        while message_id not in acks and time.monotonic()<end:client.loop(timeout=.1)
        ack=acks.get(message_id)
        if case.get('expected') is None:
            assert ack is None, f'Unexpected application ACK: {ack}'
            results.append({'message_id':message_id,'no_ack':True})
        else:
            assert ack is not None, f'No application ACK for {message_id}'
            assert ack['status'] in case['expected'],ack
            assert ack['error_code']==case.get('error'),ack
            results.append(ack)
    print(json.dumps(results))
finally:client.disconnect()
