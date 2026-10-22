"""合成报告验证严格发现、时效和分类；不读取环境、数据库或真实凭据。"""
import datetime as dt
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
import uuid
import xml.etree.ElementTree as ET

SCRIPT = Path(__file__).resolve().parents[1] / 'verify-provider-smoke.py'
TARGET = 'com.aegisos.boot.provider.ProviderSmokeIT'

class VerifySmokeTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.reports = self.root / 'reports'
        self.reports.mkdir()
        now = dt.datetime.now(dt.timezone.utc)
        self.data = dict(status='PASS', stage='COMPLETE', reason='OK', runId=str(uuid.uuid4()),
                         startedAt=(now-dt.timedelta(seconds=1)).isoformat(), completedAt=now.isoformat(),
                         httpSendAttempts=1, responseNonEmpty=True, auditVerified=True,
                         reopenedAuditVerified=True, newAuditRows=1, requestId=str(uuid.uuid4()),
                         providerName='deepseek-main', modelName='deepseek-flash',
                         sessionId='pdf16-smoke-001', configurationFingerprint='a'*64)
        self.suite = ET.Element('testsuite', name=TARGET, tests='1', failures='0', errors='0', skipped='0', timestamp=now.isoformat())
        properties=ET.SubElement(self.suite,'properties')
        ET.SubElement(properties,'property',name='aegisos.smoke.run-id',value=self.data['runId'])
        ET.SubElement(self.suite,'testcase',name='providerRoundTrip',classname=TARGET,time='0.1')
        self.summary = ET.Element('failsafe-summary',result='0')
        for key,value in dict(completed=1,errors=0,failures=0,skipped=0).items():
            ET.SubElement(self.summary,key).text=str(value)

    def run_check(self, maven=0):
        (self.root/'evidence.json').write_text(json.dumps(self.data))
        ET.ElementTree(self.suite).write(self.reports/'TEST-smoke.xml')
        ET.ElementTree(self.summary).write(self.reports/'failsafe-summary.xml')
        result=subprocess.run([sys.executable,str(SCRIPT),'--reports',str(self.reports),'--evidence',str(self.root/'evidence.json'),'--maven-exit-code',str(maven)],capture_output=True,text=True)
        self.assertNotIn('PRIVATE_SENTINEL',result.stdout+result.stderr)
        return result

    def test_only_fresh_complete_consistent_single_test_passes(self):
        r=self.run_check();self.assertEqual(r.returncode,0);self.assertTrue(r.stdout.startswith('PASS'))

    def test_zero_tests_and_skips_are_not_run(self):
        for key,value in [('tests','0'),('skipped','1')]:
            with self.subTest(key=key):
                old=self.suite.get(key);self.suite.set(key,value)
                r=self.run_check();self.assertNotEqual(r.returncode,0);self.assertTrue(r.stdout.startswith('NOT_RUN'));self.suite.set(key,old)

    def test_failures_and_maven_nonzero_cannot_pass(self):
        self.suite.set('failures','1');ET.SubElement(self.suite.find('testcase'),'failure').text='PRIVATE_SENTINEL'
        r=self.run_check();self.assertNotEqual(r.returncode,0);self.assertTrue(r.stdout.startswith('FAIL'))
        self.suite.set('failures','0');self.suite.find('testcase').remove(self.suite.find('testcase/failure'))
        self.assertNotEqual(self.run_check(maven=1).returncode,0)

    def test_preflight_failure_is_not_run_not_success(self):
        self.data.update(status='NOT_RUN',stage='PREFLIGHT',reason='CONDITIONS_MISSING',httpSendAttempts=0)
        self.suite.set('failures','1');ET.SubElement(self.suite.find('testcase'),'failure').text='NOT_RUN'
        self.summary.set('result','255')
        r=self.run_check(maven=1);self.assertNotEqual(r.returncode,0);self.assertTrue(r.stdout.startswith('NOT_RUN'))

    def test_class_loading_error_during_preflight_is_fail(self):
        # 实际Failsafe类路径回归：JSON默认预检状态不能掩盖已经执行的类加载错误。
        self.data.update(status='NOT_RUN',stage='PREFLIGHT',reason='CONDITIONS_MISSING',httpSendAttempts=0)
        self.suite.set('errors','1')
        ET.SubElement(self.suite.find('testcase'),'error').text='PRIVATE_SENTINEL'
        self.summary.set('result','255')
        result=self.run_check(maven=1)
        self.assertEqual(result.returncode,1)
        self.assertTrue(result.stdout.startswith('FAIL'))

    def test_wrong_counts_class_method_or_run_id_fail(self):
        for attr,value in [('tests','2'),('name','wrong-class')]:
            with self.subTest(attr=attr):
                old=self.suite.get(attr);self.suite.set(attr,value);self.assertNotEqual(self.run_check().returncode,0);self.suite.set(attr,old)
        self.suite.find('properties/property').set('value',str(uuid.uuid4()));self.assertNotEqual(self.run_check().returncode,0)

    def test_assertion_flags_send_count_and_rows_are_strict(self):
        for key,value in [('responseNonEmpty',False),('auditVerified',False),('reopenedAuditVerified',False),('httpSendAttempts',2),('newAuditRows',0)]:
            with self.subTest(key=key):
                old=self.data[key];self.data[key]=value;r=self.run_check();self.assertNotEqual(r.returncode,0);self.assertTrue(r.stdout.startswith('FAIL'));self.data[key]=old

    def test_stale_and_reversed_time_never_pass(self):
        self.data['startedAt']='2020-01-01T00:00:00+00:00';self.data['completedAt']='2020-01-01T00:00:01+00:00'
        r=self.run_check();self.assertNotEqual(r.returncode,0);self.assertTrue(r.stdout.startswith('NOT_RUN'))

    def test_report_roots_timeouts_and_duplicate_json_cannot_pass(self):
        self.summary.tag='wrong-root'
        self.assertNotEqual(self.run_check().returncode,0)
        self.summary.tag='failsafe-summary'
        self.summary.set('timeout','true')
        self.assertNotEqual(self.run_check().returncode,0)

    def test_wrong_method_and_missing_xml_cannot_pass(self):
        self.suite.find('testcase').set('name','wrongMethod')
        self.assertNotEqual(self.run_check().returncode,0)
        self.suite.find('testcase').set('name','providerRoundTrip')
        self.run_check()
        (self.reports/'TEST-smoke.xml').unlink()
        r=subprocess.run([sys.executable,str(SCRIPT),'--reports',str(self.reports),'--evidence',str(self.root/'evidence.json')],capture_output=True,text=True)
        self.assertNotEqual(r.returncode,0)

    def test_failsafe_report_without_timestamp_uses_mtime_and_run_id(self):
        # Failsafe 3.5.2实际testsuite不含timestamp，仍必须绑定同一runId和本次文件时间。
        self.suite.attrib.pop('timestamp')
        self.assertEqual(self.run_check().returncode,0)
        import os
        os.utime(self.reports/'TEST-smoke.xml',(1,1))
        result=subprocess.run([sys.executable,str(SCRIPT),'--reports',str(self.reports),'--evidence',str(self.root/'evidence.json')],capture_output=True,text=True)
        self.assertNotEqual(result.returncode,0)
        self.assertIn('REPORT_TIME_MISMATCH',result.stdout)

    def test_failsafe_success_serializes_nullable_result_as_null(self):
        # 隔离探针实际执行1测试：Failsafe 3.5.2 将成功 nullable result 序列化为字符串 null。
        self.summary.set('result','null')
        self.assertEqual(self.run_check().returncode,0)

    def test_missing_and_malformed_reports_are_safe_non_success(self):
        self.run_check()
        for path in [self.reports/'TEST-smoke.xml',self.root/'evidence.json',self.reports/'failsafe-summary.xml']:
            before=path.read_bytes();path.write_text('PRIVATE_SENTINEL')
            r=subprocess.run([sys.executable,str(SCRIPT),'--reports',str(self.reports),'--evidence',str(self.root/'evidence.json')],capture_output=True,text=True)
            self.assertNotEqual(r.returncode,0);self.assertNotIn('PRIVATE_SENTINEL',r.stdout+r.stderr);path.write_bytes(before)

if __name__ == '__main__':
    unittest.main()
