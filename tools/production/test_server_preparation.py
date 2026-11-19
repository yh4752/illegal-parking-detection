import copy
import importlib
import unittest


class ServerPreparationTest(unittest.TestCase):
    def setUp(self):
        self.module=importlib.import_module('prepare_server')
        self.cfg=self.module.example_input()

    def test_private_by_default_only_web_mqtt_can_be_public(self):
        bundle=self.module.render(self.cfg)
        services=bundle['compose.json']['services']
        for name,s in services.items():
            self.assertEqual(s['platform'],'linux/amd64')
            self.assertIn('@sha256:',s['image'])
            self.assertNotIn('build',s)
            for port in s.get('ports',[]): self.assertTrue(port.startswith('127.0.0.1:'))
        self.assertNotIn('ports',services['postgres'])
        self.assertNotIn('ports',services['revision-ledger'])
        self.assertEqual(services['backend']['environment']['PARKING_REVISION_LEDGER_SCOPE'],'AWS_S3')
        self.assertNotIn('aws-ledger-credentials',str(services['backend']))
        self.assertNotIn('initialize-local',services)
        self.assertEqual(services['initialize-virtual']['cap_add'],['CHOWN','DAC_OVERRIDE'])
        self.assertFalse(any(v.endswith(':/etc/grafana:ro') for v in services['grafana']['volumes']))
        self.assertEqual(services['grafana']['environment']['GF_PATHS_PLUGINS'],'/opt/parking-plugins')
        self.cfg['public_access']=True
        services=self.module.render(self.cfg)['compose.json']['services']
        exposed={n for n,s in services.items() if any(p.startswith('0.0.0.0:') for p in s.get('ports',[]))}
        self.assertEqual(exposed,{'web','mosquitto'})

    def test_unpinned_images_unsafe_domain_duplicate_devices_and_paths_fail(self):
        for field,value in [('domain','host/;bad'),('state_dir','/'),('devices',['edge-01','edge-01']),('devices',['+']),('public_access','false')]:
            cfg=copy.deepcopy(self.cfg);cfg[field]=value
            with self.assertRaises(ValueError):self.module.render(cfg)
        cfg=copy.deepcopy(self.cfg);cfg['images']['backend']='backend:latest'
        with self.assertRaises(ValueError):self.module.render(cfg)

    def test_policy_and_device_scopes(self):
        bundle=self.module.render(self.cfg)
        acl=bundle['mosquitto.acl']
        self.assertIn('user edge-01',acl)
        self.assertNotIn('topic write #',acl)
        ledger=bundle['ledger-runtime-policy.json']
        allowed=[s for s in ledger['Statement'] if s['Effect']=='Allow']
        self.assertFalse(any('s3:Delete' in str(s) for s in allowed))
        puts=[s for s in allowed if 's3:PutObject' in s['Action']]
        self.assertEqual(puts[0]['Condition']['Null']['s3:if-match'],'false')
        backup=bundle['backup-runtime-policy.json']
        self.assertIn('s3:if-none-match',str(backup))
        self.assertIn('Deny',str(bundle['ledger-bucket-policy.json']))
        self.assertIn('s3:DeleteObjectVersion',str(bundle['ledger-bucket-policy.json']))
        self.assertIn('aws:SecureTransport',str(bundle['backup-bucket-policy.json']))


if __name__=='__main__':unittest.main()
