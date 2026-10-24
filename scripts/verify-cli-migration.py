#!/usr/bin/env python3
"""只在自动创建的临时库演练V3→V4；无个人数据库输入参数，无外呼。"""
import argparse
from contextlib import closing
import fcntl
import hashlib
import json
from pathlib import Path
import sqlite3
import tempfile
import uuid

ROOT = Path(__file__).resolve().parents[1]
DB_ROOT = ROOT / 'aegisos-storage/src/main/resources/db'
V3 = (DB_ROOT / 'schema/sqlite/V003__react_runtime.sql').read_text()
V4 = (DB_ROOT / 'migration/sqlite/V004__cli_session_bindings.sql').read_text()


def snapshot(connection):
    tables = [r[0] for r in connection.execute("select name from sqlite_master where type='table' and name<>'aegisos_schema' and name not like 'sqlite_%' order by name")]
    return {name: connection.execute('select * from "' + name + '" order by rowid').fetchall() for name in tables}


def create(path):
    connection = sqlite3.connect(path)
    connection.execute('pragma foreign_keys=on')
    connection.executescript(V3 + '\nCREATE TABLE aegisos_schema(id INTEGER PRIMARY KEY CHECK(id=1),version INTEGER NOT NULL); INSERT INTO aegisos_schema VALUES(1,3);')
    return connection


def seed(connection):
    sid = str(uuid.uuid4())
    now = '2026-10-24T00:00:00Z'
    connection.execute('insert into sessions values(?,?,?,?,0,1003)', (sid, 'p', now, now))
    for n in range(501):
        pid, request, user, text = (str(uuid.uuid4()) for _ in range(4))
        connection.execute("insert into agent_processings values(?,?,?,'COMPLETED',?,?,1,null,0)", (pid, sid, str(uuid.uuid4()), now, now))
        connection.execute("insert into model_turns values(?,1,'ACCEPTED',?,?,null)", (pid, now, now))
        connection.execute('insert into llm_request_links values(?,?,1)', (request, pid))
        connection.execute("insert into llm_calls(id,request_id,attempt_no,provider_name,model_name,status,created_at,completed_at,duration_ms,retryable,session_id,success) values(?,?,1,'provider','model','SUCCEEDED',?,?,0,0,?,1)", (str(uuid.uuid4()), request, now, now, sid))
        connection.execute("insert into message_exchange_groups values(?,?,0,'USER','CLOSED',null,0,0)", (user, pid))
        connection.execute("insert into message_exchange_groups values(?,?,1,'TEXT','CLOSED',1,0,0)", (text, pid))
        connection.execute("insert into session_messages values(?,?,?, ?,?,'USER',?,null,null,?)", (str(uuid.uuid4()), sid, pid, n*2+1, user, 'safe-user-'+str(n), now))
        connection.execute("insert into session_messages values(?,?,?, ?,?,'ASSISTANT',?,null,null,?)", (str(uuid.uuid4()), sid, pid, n*2+2, text, 'safe-reply-'+str(n), now))
    # 独立的完整Tool交换，确保迁移并非只保留普通文本：USER/ASSISTANT调用/TOOL/最终回复。
    pid, request1, request2, user, exchange, text = (str(uuid.uuid4()) for _ in range(6))
    connection.execute("insert into agent_processings values(?,?,?,'COMPLETED',?,?,2,null,0)", (pid,sid,str(uuid.uuid4()),now,now))
    for iteration,request in [(1,request1),(2,request2)]:
        connection.execute("insert into model_turns values(?,?,'ACCEPTED',?,?,null)",(pid,iteration,now,now))
        connection.execute("insert into llm_request_links values(?,?,?)",(request,pid,iteration))
        connection.execute("insert into llm_calls(id,request_id,attempt_no,provider_name,model_name,status,created_at,completed_at,duration_ms,retryable,session_id,success) values(?,?,1,'provider','model','SUCCEEDED',?,?,0,0,?,1)",(str(uuid.uuid4()),request,now,now,sid))
    connection.execute("insert into message_exchange_groups values(?,?,0,'USER','CLOSED',null,0,0)",(user,pid))
    connection.execute("insert into message_exchange_groups values(?,?,1,'TOOL_EXCHANGE','CLOSED',1,1,1)",(exchange,pid))
    connection.execute("insert into message_exchange_groups values(?,?,2,'TEXT','CLOSED',2,0,0)",(text,pid))
    tool_json=json.dumps([{'id':'course-call','name':'http_get','arguments':'{}'}])
    for sequence,group,role,content,call,tools in [(1003,user,'USER','tool-user',None,None),(1004,exchange,'ASSISTANT',None,None,tool_json),(1005,exchange,'TOOL','safe-observation','course-call',None),(1006,text,'ASSISTANT','tool-final',None,None)]:
        connection.execute("insert into session_messages values(?,?,?,?,?,?,?,?,?,?)",(str(uuid.uuid4()),sid,pid,sequence,group,role,content,call,tools,now))
    connection.execute("insert into tool_invocations values(?,?,?,?,?,?,?,?,'SUCCEEDED',?,?,0,1,null,null,'RESPONDED')",(str(uuid.uuid4()),sid,pid,'course-call','http_get',1,1,'0'*64,now,now))
    connection.execute("update sessions set next_sequence_no=1007 where id=?",(sid,))
    connection.commit()
    return sid


def migrate(connection, sql=V4):
    try:
        connection.executescript(sql)
    except sqlite3.Error:
        connection.rollback()
        raise


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, help='可选安全JSON报告路径；不能传入任何已有数据库')
    args = parser.parse_args()
    try:
        with tempfile.TemporaryDirectory(prefix='aegisos-cli18-migration-') as directory:
            root = Path(directory)
            db = root/'runtime.db'
            lock_path = root/'runtime.db.lock'
            with lock_path.open('x+b') as lock:
                fcntl.lockf(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
                with closing(create(db)) as connection:
                    sid = seed(connection)
                    before = snapshot(connection)
                    with closing(sqlite3.connect(root/'backup.db')) as backup:
                        connection.backup(backup)
                    migrate(connection)
                with closing(sqlite3.connect(db)) as reopened:
                    after = snapshot(reopened)
                    assert {k: v for k,v in after.items() if k != 'session_bindings'} == before
                    assert len(after['session_messages']) == 1006
                    assert reopened.execute('select version from aegisos_schema').fetchone() == (4,)
                    assert reopened.execute('pragma foreign_key_check').fetchall() == []
                    assert reopened.execute('pragma integrity_check').fetchone() == ('ok',)
                    assert after['session_bindings'] == []
                    raw = db.read_bytes()
                    try:
                        migrate(reopened)
                        raise AssertionError('DUPLICATE_ACCEPTED')
                    except sqlite3.IntegrityError:
                        pass
                    assert db.read_bytes() == raw
                with closing(create(root/'broken.db')) as broken:
                    seed(broken)
                    old = snapshot(broken)
                    injected = V4.replace('UPDATE aegisos_schema SET version=4', 'INSERT INTO missing_table VALUES(1); UPDATE aegisos_schema SET version=4')
                    try:
                        migrate(broken, injected)
                        raise AssertionError('FAULT_ACCEPTED')
                    except sqlite3.Error:
                        pass
                    assert snapshot(broken) == old
                    assert broken.execute('select version from aegisos_schema').fetchone() == (3,)
                with closing(create(root/'forged.db')) as forged:
                    forged.execute('drop index idx_llm_calls_request')
                    forged.commit()
                    try:
                        migrate(forged)
                        raise AssertionError('FORGED_SCHEMA_ACCEPTED')
                    except sqlite3.IntegrityError:
                        pass
                    assert forged.execute('select version from aegisos_schema').fetchone() == (3,)
            report = {'status':'PASS','scope':'temporary-database-only','messages_preserved':1006,'llm_audits_preserved':503,'tool_audits_preserved':1,'complete_tool_exchange_preserved':True,'legacy_binding_rows':0,'duplicate_rejected':True,'failure_rolled_back':True,'forged_schema_rejected':True,'reopen_integrity':'ok','migration_sha256':hashlib.sha256(V4.encode()).hexdigest()}
        if args.output:
            # 报告出口也必须遵守无覆盖边界；'x' 原子拒绝已有文件和软链接。
            with args.output.open('x', encoding='utf-8') as output:
                output.write(json.dumps(report, ensure_ascii=False, indent=2)+'\n')
        print(json.dumps(report, ensure_ascii=False))
        return 0
    except Exception:
        print('FAIL:CLI_TEMPORARY_MIGRATION_PROBE')
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
