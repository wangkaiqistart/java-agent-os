#!/usr/bin/env python3
"""只在新临时目录演练 V2 backup→V3→旧行双向比较→停机恢复；无个人库参数。"""
from contextlib import closing
import fcntl
from pathlib import Path
import sqlite3
import tempfile

BASE=Path(__file__).resolve().parents[1]/'aegisos-storage/src/main/resources/db'


def verify():
    with tempfile.TemporaryDirectory(prefix='aegisos-v3-migration-') as temporary:
        root=Path(temporary)
        database=root/'fixture.db'
        backup=root/'backup.db'
        with closing(sqlite3.connect(database)) as connection:
            connection.executescript((BASE/'schema/sqlite/V002__llm_calls.sql').read_text())
            connection.execute('CREATE TABLE aegisos_schema(id INTEGER PRIMARY KEY CHECK(id=1),version INTEGER NOT NULL)')
            connection.execute('INSERT INTO aegisos_schema VALUES(1,2)')
            connection.execute("INSERT INTO llm_calls(id,request_id,attempt_no,status,created_at,retryable) VALUES('synthetic-audit','synthetic-request',1,'STARTED','2026-10-22T00:00:00Z',0)")
            connection.commit()
            original=connection.execute('SELECT * FROM llm_calls ORDER BY id').fetchall()
        # lockf 使用 POSIX record lock，与 FileChannel.tryLock 同类；flock 不能替代运行锁。
        # 只持独立 sidecar：SQLite 连接关闭会释放同进程对数据库 inode 的 record locks。
        # 与生产一致，硬链接数据库/锁文件不能作为可安全协调的单一路径使用。
        with database.with_suffix('.db.lock').open('a+b') as sidecar:
            fcntl.lockf(sidecar,fcntl.LOCK_EX|fcntl.LOCK_NB)
            assert database.stat().st_nlink==1
            assert database.with_suffix('.db.lock').stat().st_nlink==1
            with closing(sqlite3.connect(database)) as source, closing(sqlite3.connect(backup)) as target:
                source.backup(target)
            with closing(sqlite3.connect(database)) as connection:
                assert connection.execute('PRAGMA integrity_check').fetchall()==[('ok',)]
                connection.executescript((BASE/'migration/sqlite/V003__react_runtime.sql').read_text())
            # 关闭重开后从备份 ATTACH 作全列双向 EXCEPT，不依赖连接级 TEMP 表。
            with closing(sqlite3.connect(database)) as reopened:
                reopened.execute('ATTACH DATABASE ? AS old',(str(backup),))
                assert reopened.execute('SELECT * FROM llm_calls EXCEPT SELECT * FROM old.llm_calls').fetchall()==[]
                assert reopened.execute('SELECT * FROM old.llm_calls EXCEPT SELECT * FROM llm_calls').fetchall()==[]
                assert reopened.execute('SELECT version FROM aegisos_schema WHERE id=1').fetchone()==(3,)
                assert reopened.execute('PRAGMA integrity_check').fetchall()==[('ok',)]
                assert reopened.execute('PRAGMA foreign_key_check').fetchall()==[]
                assert reopened.execute("SELECT count(*) FROM sqlite_master WHERE name='sessions'").fetchone()==(1,)
            # 无运行时和写连接时恢复整个SQLite备份，不能只删除V3表或拷贝活跃主文件。
            with closing(sqlite3.connect(backup)) as source, closing(sqlite3.connect(database)) as target:
                source.backup(target)
            with closing(sqlite3.connect(database)) as restored:
                assert restored.execute('SELECT version FROM aegisos_schema').fetchone()==(2,)
                assert restored.execute('SELECT * FROM llm_calls ORDER BY id').fetchall()==original
                assert restored.execute("SELECT count(*) FROM sqlite_master WHERE name='sessions'").fetchone()==(0,)
                assert restored.execute('PRAGMA integrity_check').fetchall()==[('ok',)]
    print('PASS:TEMPORARY_V2_BACKUP_V3_BIDIRECTIONAL_COMPARE_REOPEN_AND_RESTORE')

if __name__=='__main__':
    try:
        verify()
    except Exception:
        print('FAIL:TEMPORARY_MIGRATION_REHEARSAL')
        raise SystemExit(1)
