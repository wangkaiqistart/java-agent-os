#!/usr/bin/env python3
"""仅临时工作区/回环Provider的打包CLI验收；不接受个人库或真实模型地址。"""
import argparse
import contextlib
import datetime as dt
import http.server
import json
import os
from pathlib import Path
if os.name != "nt":
    import pty
import select
import sqlite3
import ssl
import subprocess
import sys
import tempfile
import threading
import time
import urllib.request
import socket
import traceback
import shutil


def verify(jar, root):
    calls=[]
    class Provider(http.server.BaseHTTPRequestHandler):
        def do_POST(self):
            calls.append(json.loads(self.rfile.read(int(self.headers['Content-Length']))))
            body=json.dumps({'id':'loopback','object':'chat.completion','created':1,'model':'deepseek-flash',
                'choices':[{'index':0,'message':{'role':'assistant','content':'安全回复'},'finish_reason':'stop'}],
                'usage':{'prompt_tokens':1,'completion_tokens':1,'total_tokens':2}}).encode()
            self.send_response(200);self.send_header('Content-Type','application/json');self.end_headers();self.wfile.write(body)
        def log_message(self,*args): pass
    # 临时CA仅属于本次子进程；生产Provider仍要求HTTPS，不启用test profile或HTTP旁路。
    cert=root/'loopback.pem';key=root/'loopback.key';trust=root/'loopback-trust.p12'
    openssl = shutil.which('openssl')
    if not openssl and os.name == 'nt':
        candidate = Path(os.environ.get('ProgramFiles', 'C:/Program Files'))/'Git/usr/bin/openssl.exe'
        if candidate.is_file(): openssl = str(candidate)
    if not openssl: raise RuntimeError('OPENSSL_REQUIRED')
    subprocess.run([openssl,'req' ,'-x509','-newkey','rsa:2048','-nodes','-keyout',str(key),'-out',str(cert),
        '-days','1','-subj','/CN=localhost','-addext','subjectAltName=IP:127.0.0.1'],check=True,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
    subprocess.run(['keytool','-importcert','-noprompt','-alias','loopback','-file',str(cert),'-keystore',str(trust),
        '-storepass','changeit'],check=True,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
    tls=ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER);tls.load_cert_chain(cert,key)
    server=http.server.ThreadingHTTPServer(('127.0.0.1',0),Provider)
    server.socket=tls.wrap_socket(server.socket,server_side=True)
    threading.Thread(target=server.serve_forever,daemon=True).start()
    env=dict(os.environ,DEEPSEEK_API_KEY='synthetic-cli-runtime-credential', JAVA_TOOL_OPTIONS=f'-Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -Djavax.net.ssl.trustStore="{trust}" -Djavax.net.ssl.trustStorePassword=changeit' )
    def run(*words,input=b''):
        result=subprocess.run(['java','-jar',str(jar),*words],input=input,stdout=subprocess.PIPE,stderr=subprocess.PIPE,
            env=env,cwd=root,timeout=40)
        assert b'synthetic-cli-runtime-credential' not in result.stdout+result.stderr
        return result
    try:
        workspace=root/'runtime 中文 空格';workspace.mkdir()
        assert run('init','--workspace',str(workspace)).returncode==0
        config=workspace/'.aegisos/config.yml'
        config.write_text(config.read_text(encoding='utf-8').replace('https://api.deepseek.com',f'https://127.0.0.1:{server.server_port}'), encoding='utf-8')
        chat=['chat','--workspace',str(workspace),'--profile','weather']
        # 三平台均要求生产正文读取成功；/quit在batch中作为模型正文。
        secure=True
        result=run(*chat,input=b'/quit')
        data=json.loads(result.stdout)
        assert set(data)=={'sessionId','processId','status','iterationCount','reply','error','persistenceConfirmed'}
        assert result.returncode==(0 if secure else 5)
        if not secure: assert data['error']=='CONTEXT_FILE_UNAVAILABLE'
        db=workspace/'.aegisos/aegisos.db'
        with contextlib.closing(sqlite3.connect(db)) as c:
            assert c.execute('select count(*) from session_bindings').fetchone()[0]==0
        # 显式交互两消息，空白/quit不形成处理；EOF/quit重开复用同一绑定。
        before=len(calls)
        result=run(*chat,'--interactive',input='第一条\n  \n第二条\n/quit\n'.encode())
        assert result.returncode==(0 if secure else 5)
        with contextlib.closing(sqlite3.connect(db)) as c:
            assert c.execute('select count(*) from session_bindings').fetchone()[0]==1
            bound=c.execute('select session_id from session_bindings').fetchone()[0]
            n=c.execute('select count(*) from agent_processings where session_id=?',(bound,)).fetchone()[0]
            assert n==(2 if secure else 1)
            if secure:
                assert c.execute("select count(*) from llm_calls where session_id=? and status='SUCCEEDED'",(bound,)).fetchone()[0]==2
                assert c.execute('select count(distinct process_id) from llm_request_links').fetchone()[0]==3
        assert len(calls)-before==(2 if secure else 0)
        if secure: assert '第一条' in json.dumps(calls[-1],ensure_ascii=False)
        assert run(*chat,'--interactive',input=b'/quit\n').returncode==0
        if os.name != "nt":
            # PTY使JVM真实检测Console；首条quit应不创建/处理，stdout不得出现batch JSON。
            master,slave=pty.openpty()
            proc=subprocess.Popen(['java','-jar',str(jar),*chat],stdin=slave,stdout=slave,stderr=slave,env=env,cwd=root)
            os.close(slave);capture=b'';deadline=time.monotonic()+35
            try:
                while b'> ' not in capture and time.monotonic()<deadline:
                    if select.select([master],[],[],0.2)[0]:
                        try: capture+=os.read(master,8192)
                        except OSError: break
                    if proc.poll() is not None: break
                assert b'> ' in capture
                os.write(master,b'/quit\n')
                assert proc.wait(timeout=15)==0
            finally:
                if proc.poll() is None: proc.kill();proc.wait()
                os.close(master)
        with contextlib.closing(sqlite3.connect(db)) as c:
            assert c.execute('select session_id from session_bindings').fetchone()[0]==bound
            assert c.execute('select count(*) from agent_processings where session_id=?',(bound,)).fetchone()[0]==n
        assert run(*chat,'--batch',input=b'final').returncode==(0 if secure else 5)
        # 管理命令和查询必须基于同一个真实持久库，不是fixture对象计数。
        def snapshot():
            return {str(p.relative_to(workspace)):(p.read_bytes(),p.stat().st_mtime_ns) for p in workspace.rglob('*') if p.is_file()}
        before_files=snapshot()
        listing=run('session','list','--workspace',str(workspace))
        assert listing.returncode==0
        page=json.loads(listing.stdout);assert page['limit']==20 and page['offset']==0 and len(page['items'])>=2
        assert all(set(item)=={'sessionId','profileName','createdAt','updatedAt','messageCount','processingCount'} for item in page['items'])
        status=run('status','--workspace',str(workspace));assert status.returncode==0 and json.loads(status.stdout)['database']=='AVAILABLE'
        assert run('gateway','--workspace',str(workspace)).returncode==6
        assert snapshot()==before_files
        args=['profile','create','--workspace',str(workspace),'--name','demo','--provider','deepseek-main','--model','deepseek-flash']
        assert run(*args).returncode==0
        assert run(*args).returncode==2
        assert run('profile','show','--workspace',str(workspace),'--name','demo').returncode==0
        assert run('profile','delete','--workspace',str(workspace),'--name','demo').returncode==0
        assert not (workspace/'.aegisos/agents/demo').exists()
        with socket.socket() as s:
            s.bind(('127.0.0.1',0));port=s.getsockname()[1]
        serving=subprocess.Popen(['java','-jar',str(jar),'serve','--workspace',str(workspace),'--port',str(port)],
            stdout=subprocess.PIPE,stderr=subprocess.PIPE,env=env,cwd=root)
        try:
            deadline=time.monotonic()+35;ready=False
            while time.monotonic()<deadline and serving.poll() is None:
                try:
                    with urllib.request.urlopen(f'http://127.0.0.1:{port}/actuator/health',timeout=1) as response:
                        ready=response.status==200 and json.load(response)['status']=='UP'
                    if ready: break
                except (OSError,ValueError): time.sleep(0.1)
            assert ready
            assert run('session','list','--workspace',str(workspace)).returncode==5
        finally:
            serving.terminate()
            try: serving.communicate(timeout=20)
            except subprocess.TimeoutExpired: serving.kill();serving.communicate();raise
        assert run(*chat,'--interactive',input=b'/quit\n').returncode==0
        with contextlib.closing(sqlite3.connect(db)) as c:
            assert c.execute('select session_id from session_bindings').fetchone()[0]==bound
            assert c.execute('select count(*) from agent_processings where session_id=?',(bound,)).fetchone()[0]==n
        print('PASS:JAR_CRUD_READONLY_NO_WRITES_SERVE_HEALTH_STOP_CHAT_REOPEN_GATEWAY_DISABLED')
        print('PASS:JAR_PIPE_BATCH_PTY_REPL_QUIT_BINDING_REOPEN_RESOURCES')
        print('PASS:NATIVE_TWO_PROCESSINGS_AUDIT_HISTORY')
        print('NOT_APPLICABLE:WINDOWS_PTY_USE_EXPLICIT_INTERACTIVE' if os.name == 'nt' else 'PASS:NATIVE_PTY_REPL')
    finally:
        server.shutdown();server.server_close()


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jar',type=Path,required=True)
    parser.add_argument('--built-after',required=True)
    args=parser.parse_args()
    try:
        anchor=dt.datetime.fromisoformat(args.built_after.replace('Z','+00:00'))
        assert anchor.tzinfo and args.jar.stat().st_mtime>=anchor.timestamp()
        with tempfile.TemporaryDirectory(prefix='aegisos-cli-runtime-') as directory:
            verify(args.jar.resolve(),Path(directory))
        return 0
    except Exception as error:
        print('FAIL:CLI_RUNTIME_PROBE:'+type(error).__name__+':line='+str(traceback.extract_tb(error.__traceback__)[-1].lineno))
        return 1

if __name__=='__main__': raise SystemExit(main())
