#!/usr/bin/env python3
"""核验本轮真实 ReAct XML、元数据及只读审计；不读取凭据或会话正文。"""
from contextlib import closing
import argparse
import datetime as dt
from decimal import Decimal
import hashlib
import json
from pathlib import Path
import re
import sqlite3
import xml.etree.ElementTree as ET
from zoneinfo import ZoneInfo
import yaml

TARGET = 'com.aegisos.boot.react.ReactWeatherSmokeIT'
BASE = Path(__file__).resolve().parents[1]/'aegisos-boot'/'target'
FIELDS = set('runId startedAt completedAt configFingerprint sessionId processIds statuses acceptedIterations logicalRequests physicalSends toolExecutions weatherGets weatherValid observationForwarded factMatched advicePresent auditReopened associationValid status errorCode'.split())
FLAGS = 'weatherValid observationForwarded factMatched advicePresent auditReopened associationValid'.split()


def small(path):
    with Path(path).open('rb') as stream:
        data = stream.read(1048577)
    if len(data)>1048576:
        raise ValueError('SIZE')
    return data


def exact(value, keys):
    if type(value) is not dict or set(value)!=set(keys.split()):
        raise ValueError('FIELDS')


def load_config(path):
    raw = small(path).decode('utf-8')
    if len(raw.encode('utf-8'))>262144:
        raise ValueError('SIZE')
    for event in yaml.parse(raw):
        if isinstance(event, yaml.events.AliasEvent) or getattr(event,'anchor',None):
            raise ValueError('ALIAS')
    class Strict(yaml.SafeLoader):
        pass
    def mapping(loader,node):
        pairs=loader.construct_pairs(node,deep=True)
        result={}
        for key,value in pairs:
            if type(key) is not str or key in result:
                raise ValueError('DUPLICATE')
            result[key]=value
        return result
    Strict.add_constructor(yaml.resolver.BaseResolver.DEFAULT_MAPPING_TAG,mapping)
    tree=yaml.load(raw,Loader=Strict)
    exact(tree,'run-id workspace-parent provider weather messages budgets')
    if not isinstance(tree['run-id'],str) or not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_-]{0,63}',tree['run-id']):
        raise ValueError('RUN')
    parent=Path(tree['workspace-parent'])
    if not parent.is_absolute() or not parent.is_dir() or parent.is_symlink():
        raise ValueError('PARENT')
    # macOS java.io.tmpdir 与 Python tempfile 均由系统分配；Linux 的批准父目录在 /tmp。
    import tempfile
    if not parent.resolve().is_relative_to(Path(tempfile.gettempdir()).resolve()):
        raise ValueError('TEMP_PARENT')
    if tree['provider']!={'name':'deepseek-main','model':'deepseek-flash','base-url':'https://api.deepseek.com','credential-ref':'env:DEEPSEEK_API_KEY','thinking-mode':'disabled'}:
        raise ValueError('PROVIDER')
    weather=tree['weather']
    exact(weather,'city latitude longitude zone target current-fields')
    for field,limit in [('latitude',90),('longitude',180)]:
        n=Decimal(str(weather[field]))
        if type(weather[field]) not in (int,float) or not n.is_finite() or abs(n)>limit:
            raise ValueError('COORDINATE')
    if not isinstance(weather['city'],str) or not weather['city'].strip():
        raise ValueError('CITY')
    ZoneInfo(weather['zone'])
    if weather['target']!={'scheme':'https','host':'api.open-meteo.com','port':443,'method':'GET','paths':['/v1/forecast'],'query-keys':['latitude','longitude','current','timezone']} or weather['current-fields']!=['temperature_2m','weather_code','wind_speed_10m']:
        raise ValueError('TARGET')
    if type(tree['messages']) is not list or len(tree['messages'])!=2 or any(type(m) is not str or not m.strip() or len(m.encode('utf-8'))>32768 for m in tree['messages']):
        raise ValueError('MESSAGES')
    b=tree['budgets']
    exact(b,'max-iterations-per-processing provider-max-retries max-model-requests max-weather-gets')
    if type(b['provider-max-retries']) is not int or b['provider-max-retries']!=0:
        raise ValueError('RETRIES')
    for key,limit in [('max-iterations-per-processing',3),('max-model-requests',6),('max-weather-gets',2)]:
        if type(b[key]) is not int or not 1<=b[key]<=limit:
            raise ValueError('BUDGET')
    return tree


def canonical(value):
    if type(value) is dict:
        return '{'+','.join(canonical(k)+':'+canonical(value[k]) for k in sorted(value))+'}'
    if type(value) is list:
        return '['+','.join(canonical(x) for x in value)+']'
    if type(value) in (int,float):
        d=Decimal(str(value))
        return format(d.normalize(),'f') if d else '0'
    if value is True:
        return 'true'
    if value is False:
        return 'false'
    if value is None:
        return 'null'
    return '"'+''.join('\\'+c if c in '"\\' else '\\u%04x'%ord(c) if ord(c)<32 else c for c in value)+'"'


def fingerprint(tree):
    return hashlib.sha256(canonical({k:v for k,v in tree.items() if k!='workspace-parent'}).encode('utf-8')).hexdigest()


def instant(value):
    parsed=dt.datetime.fromisoformat(value.replace('Z','+00:00'))
    if parsed.tzinfo is None or parsed.utcoffset()!=dt.timedelta(0):
        raise ValueError('UTC_REQUIRED')
    return parsed


def audit_valid(tree, data):
    db=Path(tree['workspace-parent'])/tree['run-id']/'.aegisos'/'aegisos.db'
    if not db.is_file() or db.is_symlink():
        return False
    with closing(sqlite3.connect(db.resolve().as_uri()+'?mode=ro',uri=True)) as c:
        c.execute('PRAGMA query_only=ON')
        if c.execute('PRAGMA integrity_check').fetchone()!=('ok',) or c.execute('PRAGMA foreign_key_check').fetchall():
            return False
        if c.execute('SELECT id,profile_name FROM sessions').fetchall()!=[(data['sessionId'],'weather')]:
            return False
        processes=c.execute('SELECT id,session_id,status,iteration_count FROM agent_processings').fetchall()
        actual={row[0]:row[1:] for row in processes}
        expected={p:(data['sessionId'],'COMPLETED',n) for p,n in zip(data['processIds'],data['acceptedIterations'])}
        if actual!=expected:
            return False
        calls=c.execute('SELECT l.request_id,l.session_id,l.status,l.attempt_no,l.success,l.provider_name,l.model_name,k.process_id,k.iteration_no FROM llm_calls l LEFT JOIN llm_request_links k ON k.request_id=l.request_id').fetchall()
        wanted={(p,i) for p,n in zip(data['processIds'],data['acceptedIterations']) for i in range(1,n+1)}
        if len(calls)!=data['physicalSends'] or len({r[0] for r in calls})!=len(calls) or {(r[7],r[8]) for r in calls}!=wanted:
            return False
        if any(r[1:7]!=(data['sessionId'],'SUCCEEDED',1,1,'deepseek-main','deepseek-flash') for r in calls):
            return False
        if c.execute('SELECT count(*) FROM llm_request_links').fetchone()[0]!=len(calls):
            return False
        turns=c.execute('SELECT process_id,iteration_no,status FROM model_turns').fetchall()
        if len(turns)!=len(wanted) or {(p,i) for p,i,s in turns if s=='ACCEPTED'}!=wanted:
            return False
        tools=c.execute('SELECT id,session_id,process_id,raw_call_id,status,success,tool_name,external_outcome,first_iteration_no FROM tool_invocations').fetchall()
        if len(tools)!=data['toolExecutions']:
            return False
        for aid,session,process,call,status,success,name,outcome,iteration in tools:
            if session!=data['sessionId'] or process not in expected or (status,success,name,outcome)!=('SUCCEEDED',1,'http_get','RESPONDED'):
                return False
            # 只提取白名单元数据，SQLite 正文不返回 Python；每条终态审计必须有同组可靠观察。
            rows=c.execute("SELECT json_extract(m.content,'$.auditId'),json_extract(m.content,'$.success'),g.state,g.expected_observations,g.committed_observations FROM session_messages m JOIN message_exchange_groups g ON g.id=m.group_id WHERE m.process_id=? AND m.tool_call_id=? AND m.role='TOOL' AND g.iteration_no=?",(process,call,iteration)).fetchall()
            if len(rows)!=1 or rows[0][0:3]!=(aid,1,'CLOSED') or rows[0][3]!=rows[0][4]:
                return False
        return True


def verify(config,started_at,maven_exit,reports=None,evidence=None):
    reports=reports or BASE/'react-smoke-failsafe-reports'
    evidence=evidence or BASE/'react-smoke-result.json'
    if not Path(config).is_file():
        return 'NOT_RUN','CONFIGURATION_MISSING'
    try:
        tree=load_config(config)
        anchor=instant(started_at)
    except Exception:
        return 'FAIL','CONFIGURATION_OR_TIME_INVALID'
    try:
        suites=[(p,ET.fromstring(small(p))) for p in Path(reports).glob('TEST-*.xml')]
        matching=[(p,s) for p,s in suites if s.get('name')==TARGET]
        if not matching:
            return 'NOT_RUN','TARGET_NOT_DISCOVERED'
        if len(suites)!=1 or len(matching)!=1:
            return 'FAIL','UNEXPECTED_DISCOVERY'
        xml_path,suite=matching[0]
        cases=suite.findall('testcase')
        if int(suite.get('tests','0'))==0 or not cases or int(suite.get('skipped','0')) or any(c.find('skipped') is not None for c in cases):
            return 'NOT_RUN','NO_EXECUTION_OR_SKIPPED'
        if len(cases)!=1 or suite.get('tests')!='1' or cases[0].get('classname')!=TARGET or cases[0].get('name')!='weatherConversation':
            return 'FAIL','UNEXPECTED_TEST_IDENTITY'
        def unique(pairs):
            result={}
            for key,value in pairs:
                if key in result:
                    raise ValueError('DUPLICATE_EVIDENCE_KEY')
                result[key]=value
            return result
        data=json.loads(small(evidence),object_pairs_hook=unique)
        if set(data)!=FIELDS:
            return 'FAIL','EVIDENCE_FIELDS_INVALID'
        start,end=instant(data['startedAt']),instant(data['completedAt'])
        now=dt.datetime.now(dt.timezone.utc)
        xml_time=dt.datetime.fromtimestamp(xml_path.stat().st_mtime,dt.timezone.utc)
        props={p.get('name'):p.get('value') for p in suite.findall('properties/property')}
        if data['runId']!=tree['run-id'] or props.get('aegisos.react-smoke.run-id')!=tree['run-id'] or data['configFingerprint']!=fingerprint(tree):
            return 'FAIL','RUN_OR_FINGERPRINT_MISMATCH'
        if not anchor<=start<=end<=now+dt.timedelta(seconds=15) or now-end>dt.timedelta(minutes=15) or not anchor<=xml_time<=end+dt.timedelta(seconds=15):
            return 'FAIL','STALE_OR_TIME_MISMATCH'
        if int(suite.get('errors','0')) or cases[0].find('error') is not None:
            return 'FAIL','HARNESS_ERROR'
        counters=('logicalRequests','physicalSends','toolExecutions','weatherGets')
        if data['status']=='NOT_RUN' and data['errorCode']=='CONDITIONS_MISSING':
            return ('NOT_RUN','CONDITIONS_MISSING') if all(type(data[k]) is int and data[k]==0 for k in counters) else ('FAIL','PREFLIGHT_SENT_REQUEST')
        if maven_exit!=0 or int(suite.get('failures','0')) or cases[0].find('failure') is not None:
            return 'FAIL','MAVEN_OR_TEST_FAILED'
        if data['status']!='PASS' or data['errorCode'] is not None or any(data[k] is not True for k in FLAGS):
            return 'FAIL','ASSERTION_MISMATCH'
        b=tree['budgets']
        rounds=data['acceptedIterations']
        if type(rounds) is not list or len(rounds)!=2 or any(type(n) is not int or not 1<=n<=b['max-iterations-per-processing'] for n in rounds):
            return 'FAIL','ITERATIONS_INVALID'
        if data['statuses']!=['COMPLETED','COMPLETED'] or len(data['processIds'])!=2 or len(set(data['processIds']))!=2:
            return 'FAIL','PROCESSING_INVALID'
        if any(type(data[k]) is not int for k in counters) or not 1<=data['weatherGets']==data['toolExecutions']<=b['max-weather-gets'] or not sum(rounds)==data['logicalRequests']==data['physicalSends']<=b['max-model-requests']:
            return 'FAIL','BUDGET_OR_COUNT_MISMATCH'
        if not audit_valid(tree,data):
            return 'FAIL','REOPENED_AUDIT_MISMATCH'
        return 'PASS','WEATHER_FACTS_AND_AUDIT_VERIFIED'
    except (FileNotFoundError,NotADirectoryError):
        return 'NOT_RUN','EVIDENCE_UNAVAILABLE'
    except Exception:
        return 'FAIL','EVIDENCE_INVALID'


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--config',required=True,type=Path)
    parser.add_argument('--started-at',required=True)
    parser.add_argument('--maven-exit-code',required=True,type=int)
    args=parser.parse_args()
    status,reason=verify(args.config,args.started_at,args.maven_exit_code)
    print(status+':'+reason)
    return 0 if status=='PASS' else 2 if status=='NOT_RUN' else 1

if __name__=='__main__':
    raise SystemExit(main())
