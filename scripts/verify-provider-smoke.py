#!/usr/bin/env python3
"""核验本次 Failsafe XML + 安全 JSON；不读取环境、凭据、回复或数据库。"""
import argparse
import datetime as dt
import json
from pathlib import Path
import uuid
import xml.etree.ElementTree as ET

TARGET = 'com.aegisos.boot.provider.ProviderSmokeIT'


def instant(value):
    """兼容 Failsafe 的本地时区时间与 JSON 的 UTC ISO 时间。"""
    parsed = dt.datetime.fromisoformat(value.replace('Z', '+00:00'))
    return parsed.astimezone(dt.timezone.utc)


def small_read(path):
    with path.open('rb') as stream:
        value = stream.read(1048577)
    if len(value) > 1048576:
        raise ValueError()
    return value


def verify(reports, evidence, maven_exit=None):
    """只有明确的唯一执行和全部断言成立才成功；任何输入错误只返回固定分类。"""
    try:
        data = json.loads(small_read(evidence))
        if not isinstance(data, dict):
            raise ValueError()
        start, end = instant(data['startedAt']), instant(data['completedAt'])
        now = dt.datetime.now(dt.timezone.utc)
        if end < start or (now-end).total_seconds() > 900 or (end-now).total_seconds() > 15:
            return 'NOT_RUN', 'STALE_EVIDENCE'
        uuid.UUID(data['runId'])
    except (OSError, ValueError, KeyError, TypeError, AttributeError):
        return 'NOT_RUN', 'EVIDENCE_UNAVAILABLE'
    try:
        suite_files = [(path, ET.fromstring(small_read(path))) for path in reports.glob('TEST-*.xml')]
        suites = [suite for _, suite in suite_files]
        matching = [(path, suite) for path, suite in suite_files if suite.get('name') == TARGET]
        if len(matching) != 1:
            return 'NOT_RUN', 'TARGET_NOT_DISCOVERED'
        suite_path, suite = matching[0]
        if suite.tag != 'testsuite':
            return 'NOT_RUN', 'REPORT_UNAVAILABLE'
        cases = suite.findall('testcase')
        if int(suite.get('tests', '0')) == 0 or not cases:
            return 'NOT_RUN', 'NO_EXECUTION'
        if int(suite.get('skipped', '0')) or any(case.find('skipped') is not None for case in cases):
            return 'NOT_RUN', 'SKIPPED'
        if len(cases) != 1 or int(suite.get('tests', '0')) != 1 or len(suites) != 1:
            return 'FAIL', 'UNEXPECTED_TEST_COUNT'
        case = cases[0]
        if case.get('classname') != TARGET or case.get('name') != 'providerRoundTrip':
            return 'FAIL', 'UNEXPECTED_TEST_IDENTITY'
        properties = {item.get('name'): item.get('value') for item in suite.findall('properties/property')}
        if properties.get('aegisos.smoke.run-id') != data['runId']:
            return 'NOT_RUN', 'RUN_ID_MISMATCH'
        # Failsafe 3.5.2的真实XML可无timestamp；该格式改用文件时间，runId仍须一致。
        stamp = (instant(suite.attrib['timestamp']) if 'timestamp' in suite.attrib
                 else dt.datetime.fromtimestamp(suite_path.stat().st_mtime, dt.timezone.utc))
        if stamp < start-dt.timedelta(seconds=15) or stamp > end+dt.timedelta(seconds=15):
            return 'NOT_RUN', 'REPORT_TIME_MISMATCH'
        # 已执行测试的类加载/运行错误优先于JSON的初始预检状态；不能误报为缺凭据。
        if data.get('status') == 'NOT_RUN' and data.get('stage') == 'PREFLIGHT':
            if type(data.get('httpSendAttempts')) is not int or data['httpSendAttempts'] != 0:
                return 'FAIL', 'PREFLIGHT_SENT_REQUEST'
            if int(suite.get('errors', '0')) or case.find('error') is not None:
                return 'FAIL', 'HARNESS_ERROR'
            return 'NOT_RUN', 'CONDITIONS_MISSING'
        summary = ET.fromstring(small_read(reports/'failsafe-summary.xml'))
        if summary.tag != 'failsafe-summary':
            return 'NOT_RUN', 'REPORT_UNAVAILABLE'
        if summary.get('timeout', 'false') != 'false' or summary.findtext('failureMessage', '').strip():
            return 'FAIL', 'MAVEN_FAILED'
        if (maven_exit is not None and maven_exit != 0) or summary.get('result') not in ('0', 'null'):
            return 'FAIL', 'MAVEN_FAILED'
        if int(suite.get('failures', '0')) or int(suite.get('errors', '0')) or case.find('failure') is not None or case.find('error') is not None:
            return 'FAIL', 'TEST_FAILED'
        for name, expected in dict(completed=1, errors=0, failures=0, skipped=0).items():
            if int(summary.findtext(name, '-1')) != expected:
                return 'FAIL', 'MAVEN_REPORT_MISMATCH'
        uuid.UUID(data['requestId'])
        flags = ('responseNonEmpty', 'auditVerified', 'reopenedAuditVerified')
        if (data.get('status') != 'PASS' or data.get('stage') != 'COMPLETE'
                or any(data.get(flag) is not True for flag in flags)
                or type(data.get('httpSendAttempts')) is not int or data['httpSendAttempts'] != 1
                or type(data.get('newAuditRows')) is not int or data['newAuditRows'] != 1
                or data.get('providerName') != 'deepseek-main' or data.get('modelName') != 'deepseek-flash'
                or data.get('sessionId') != 'pdf16-smoke-001'
                or not isinstance(data.get('configurationFingerprint'), str)
                or len(data['configurationFingerprint']) != 64):
            return 'FAIL', 'ASSERTION_MISMATCH'
        return 'PASS', 'SINGLE_REQUEST_AND_AUDIT_VERIFIED'
    except (OSError, ValueError, KeyError, TypeError, AttributeError, ET.ParseError):
        return 'NOT_RUN', 'REPORT_UNAVAILABLE'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--reports', required=True, type=Path)
    parser.add_argument('--evidence', required=True, type=Path)
    # 外层流程仍须保存 Maven 退出码；传入非零值绝不能被报告成功覆盖。
    parser.add_argument('--maven-exit-code', type=int)
    args = parser.parse_args()
    status, reason = verify(args.reports, args.evidence, args.maven_exit_code)
    print(status + ':' + reason)
    return 0 if status == 'PASS' else 2 if status == 'NOT_RUN' else 1


if __name__ == '__main__':
    raise SystemExit(main())
