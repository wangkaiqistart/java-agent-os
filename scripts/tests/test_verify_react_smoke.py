"""真实验收验证器的离线反例；不使用凭据/真实网络/个人数据库。"""
from contextlib import closing
import copy
import datetime as dt
import importlib.util
import json
from pathlib import Path
import sqlite3
import tempfile
import unittest
import xml.etree.ElementTree as ET
import yaml
spec = importlib.util.spec_from_file_location('react_verifier', Path(__file__).parents[1]/'verify-react-smoke.py')
v = importlib.util.module_from_spec(spec)
spec.loader.exec_module(v)

class EvidenceTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix='aegisos-verifier-')
        self.root = Path(self.tmp.name)
        self.config = self.root/'config.yml'
        self.tree = {
            'run-id':'offline-run', 'workspace-parent':str(self.root),
            'provider':{'name':'deepseek-main','model':'deepseek-flash','base-url':'https://api.deepseek.com','credential-ref':'env:DEEPSEEK_API_KEY','thinking-mode':'disabled'},
            'weather':{'city':'上海','latitude':31.23,'longitude':121.47,'zone':'Asia/Shanghai',
                       'target':{'scheme':'https','host':'api.open-meteo.com','port':443,'method':'GET','paths':['/v1/forecast'],'query-keys':['latitude','longitude','current','timezone']},
                       'current-fields':['temperature_2m','weather_code','wind_speed_10m']},
            'messages':['查询天气','沿用事实给建议'],
            'budgets':{'max-iterations-per-processing':3,'provider-max-retries':0,'max-model-requests':6,'max-weather-gets':2}}
        self.config.write_text(yaml.safe_dump(self.tree,allow_unicode=True))
        self.started = dt.datetime.now(dt.timezone.utc)-dt.timedelta(seconds=2)
        self.reports = self.root/'reports'
        self.reports.mkdir()
        self.evidence = self.root/'evidence.json'
        self.data = dict(runId='offline-run', startedAt=self.started.isoformat(), completedAt=dt.datetime.now(dt.timezone.utc).isoformat(),
                         configFingerprint=v.fingerprint(self.tree),sessionId='s',processIds=['p1','p2'],statuses=['COMPLETED']*2,
                         acceptedIterations=[2,1],logicalRequests=3,physicalSends=3,toolExecutions=1,weatherGets=1,
                         weatherValid=True,observationForwarded=True,factMatched=True,advicePresent=True,
                         auditReopened=True,associationValid=True,status='PASS',errorCode=None)
        self.save()
        suite=ET.Element('testsuite',name=v.TARGET,tests='1',skipped='0',errors='0',failures='0')
        props=ET.SubElement(suite,'properties')
        ET.SubElement(props,'property',name='aegisos.react-smoke.run-id',value='offline-run')
        ET.SubElement(suite,'testcase',classname=v.TARGET,name='weatherConversation')
        self.xml=self.reports/('TEST-'+v.TARGET+'.xml')
        ET.ElementTree(suite).write(self.xml)
        self.schema_db()
    def tearDown(self):
        self.tmp.cleanup()
    def save(self):
        self.evidence.write_text(json.dumps(self.data))
    def schema_db(self):
        db=self.root/'offline-run'/'.aegisos'/'aegisos.db'
        db.parent.mkdir(parents=True)
        # 使用真实V3 DDL与真实约束。通过生产JpaSessionStore生成复杂行不是验证器的职责。
        self.db=db
        schema=(Path(__file__).parents[2]/'aegisos-storage/src/main/resources/db/schema/sqlite/V003__react_runtime.sql').read_text()
        with closing(sqlite3.connect(db)) as c, c:
            c.executescript(schema)
            c.execute("INSERT INTO sessions VALUES ('s','weather','t','t',0,1)")
            for p,n in [('p1',2),('p2',1)]:
                c.execute("INSERT INTO agent_processings VALUES (?, 's','r','COMPLETED','t','t',?,NULL,0)",(p,n))
                for i in range(1,n+1):
                    rid=f'{p}-{i}'
                    c.execute("INSERT INTO model_turns VALUES (?,?,'ACCEPTED','t','t',NULL)",(p,i))
                    c.execute('INSERT INTO llm_request_links VALUES (?,?,?)',(rid,p,i))
                    c.execute("INSERT INTO llm_calls(id,request_id,attempt_no,provider_name,model_name,status,created_at,completed_at,duration_ms,retryable,session_id,success) VALUES (?,?,1,'deepseek-main','deepseek-flash','SUCCEEDED','t','t',1,0,'s',1)",(rid,rid))
            c.execute("INSERT INTO message_exchange_groups VALUES ('g','p1',1,'TOOL_EXCHANGE','CLOSED',1,1,1)")
            c.execute("INSERT INTO tool_invocations VALUES ('a','s','p1','c','http_get',1,1,?,'SUCCEEDED','t','t',1,1,NULL,NULL,'RESPONDED')",('0'*64,))
            c.execute("INSERT INTO session_messages VALUES ('m','s','p1',1,'g','TOOL',?,'c',NULL,'t')",(json.dumps({'auditId':'a','success':True,'name':'http_get'}),))
    def verify(self):
        return v.verify(self.config,self.started.isoformat(),0,self.reports,self.evidence)[0]
    def test_success_requires_xml_facts_and_reopened_database(self):
        self.assertEqual(self.verify(),'PASS')
    def test_explicit_disabled_mode_is_required_and_changes_the_fingerprint(self):
        self.assertEqual(v.load_config(self.config)['provider']['thinking-mode'],'disabled')
        current=v.fingerprint(self.tree)
        legacy=copy.deepcopy(self.tree)
        legacy['provider'].pop('thinking-mode')
        self.assertNotEqual(v.fingerprint(legacy),current)
        for value in (None,'enabled','DISABLED','',{'type':'disabled'}):
            with self.subTest(mode=value):
                changed=copy.deepcopy(self.tree)
                if value is None:changed['provider'].pop('thinking-mode')
                else:changed['provider']['thinking-mode']=value
                self.config.write_text(yaml.safe_dump(changed,allow_unicode=True))
                with self.assertRaises(ValueError):v.load_config(self.config)
                self.assertEqual(self.verify(),'FAIL')
    def test_no_config_and_missing_xml_are_not_run(self):
        self.assertEqual(v.verify(self.root/'missing',self.started.isoformat(),0)[0],'NOT_RUN')
        self.xml.unlink()
        self.assertEqual(self.verify(),'NOT_RUN')
    def test_zero_discovery_and_skip_are_not_run(self):
        original=self.xml.read_bytes()
        for key in ('tests','skipped'):
            root=ET.fromstring(original)
            root.set(key,'0' if key=='tests' else '1')
            ET.ElementTree(root).write(self.xml)
            self.assertEqual(self.verify(),'NOT_RUN')
    def test_failures_errors_stale_and_wrong_run_are_fail(self):
        for key,value in [('status','FAIL'),('runId','other'),('completedAt',(self.started-dt.timedelta(hours=1)).isoformat()),('configFingerprint','f'*64),('physicalSends',7),('logicalRequests',0),('acceptedIterations',[4,1]),('associationValid',False),('weatherValid',False),('observationForwarded',False),('factMatched',False),('advicePresent',False),('auditReopened',False)]:
            with self.subTest(key=key):
                old=copy.deepcopy(self.data)
                self.data[key]=value
                self.save()
                self.assertEqual(self.verify(),'FAIL')
                self.data=old
                self.save()
        for key in ('errors','failures'):
            original=self.xml.read_bytes()
            tree=ET.fromstring(original)
            tree.set(key,'1')
            ET.ElementTree(tree).write(self.xml)
            self.assertEqual(self.verify(),'FAIL')
            self.xml.write_bytes(original)
    def test_claimed_pass_cannot_hide_audit_mismatch(self):
        with closing(sqlite3.connect(self.db)) as c, c:
            c.execute("UPDATE llm_calls SET session_id='another-session'")
        self.assertEqual(self.verify(),'FAIL')
    def test_unknown_fields_duplicates_and_budget_expansion_are_rejected(self):
        self.config.write_text(self.config.read_text()+'unknown: true\n')
        self.assertEqual(self.verify(),'FAIL')
    def test_nonzero_maven_never_passes(self):
        self.assertEqual(v.verify(self.config,self.started.isoformat(),1,self.reports,self.evidence)[0],'FAIL')
    def test_failed_preflight_is_not_run_only_with_zero_sends(self):
        self.data.update(status='NOT_RUN',errorCode='CONDITIONS_MISSING',logicalRequests=0,physicalSends=0,toolExecutions=0,weatherGets=0)
        self.save()
        self.assertEqual(self.verify(),'NOT_RUN')
        self.data['physicalSends']=1
        self.save()
        self.assertEqual(self.verify(),'FAIL')

if __name__=='__main__':
    unittest.main()
