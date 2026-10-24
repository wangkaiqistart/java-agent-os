#!/usr/bin/env python3
"""本轮打包 JAR 的离线 init 探针；Linux strace 核验零网络/零监听。"""
import argparse
import datetime as dt
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jar',required=True,type=Path)
    parser.add_argument('--built-after',required=True)
    args=parser.parse_args()
    try:
        anchor=dt.datetime.fromisoformat(args.built_after.replace('Z','+00:00'))
        if anchor.tzinfo is None or args.jar.stat().st_mtime<anchor.timestamp():
            raise ValueError()
        trace=shutil.which('strace')
        if not trace:
            print('NOT_RUN:STRACE_REQUIRED_FOR_NETWORK_PROOF')
            return 2
        jar=args.jar.resolve()
        with tempfile.TemporaryDirectory(prefix='aegisos-cli-package-') as directory:
            root=Path(directory)
            workspace=root/'workspace'
            workspace.mkdir()
            env=dict(os.environ)
            env['DEEPSEEK_API_KEY']='synthetic-init-sentinel-credential'
            original=None
            for ordinal in range(2):
                log=root/f'network-{ordinal}.trace'
                classes=root/f'classes-{ordinal}.log'
                command=[trace,'-f','-e','trace=network','-o',str(log),'java',f'-Xlog:class+load:file={classes}','-jar',str(jar),'init','--workspace',str(workspace)]
                run=subprocess.run(command,input=b'',stdout=subprocess.PIPE,stderr=subprocess.PIPE,env=env,timeout=30,cwd=root)
                if run.returncode!=(0 if ordinal==0 else 2):
                    raise ValueError()
                result=json.loads(run.stdout)
                if ordinal==0 and result!={'status':'INITIALIZED'}:
                    raise ValueError()
                if ordinal==1 and result.get('status')!='FAILED':
                    raise ValueError()
                if b'synthetic-init-sentinel-credential' in run.stdout+run.stderr:
                    raise ValueError()
                # 不禁止 JVM 的本地 AF_UNIX 管道；所有 IP send/connect/bind/listen 均必须为零。
                network=log.read_text()
                if re.search(r'(?:connect|bind|sendto|sendmsg)\(.*AF_INET|\blisten\(',network):
                    raise ValueError()
                loaded=classes.read_text()
                if any(name in loaded for name in ('com.zaxxer.hikari.HikariDataSource','com.aegisos.boot.config.EnvironmentCredentialResolver','org.springframework.context.annotation.AnnotationConfigApplicationContext')):
                    raise ValueError()
                snapshot={str(p.relative_to(workspace)):p.read_bytes() for p in workspace.rglob('*') if p.is_file()}
                if ordinal==0:
                    original=snapshot
                elif snapshot!=original:
                    raise ValueError()
                if any(p.suffix in ('.db','.sqlite','.lock') for p in workspace.rglob('*')):
                    raise ValueError()
                if not (workspace/'.aegisos/agents/weather/profile.yml').is_file():
                    raise ValueError()
            print('PASS:CURRENT_JAR_INIT_NO_OVERWRITE_NO_DATABASE_NO_IP_NETWORK')
            return 0
    except Exception:
        print('FAIL:CLI_PACKAGE_PROBE')
        return 1

if __name__=='__main__':
    raise SystemExit(main())
