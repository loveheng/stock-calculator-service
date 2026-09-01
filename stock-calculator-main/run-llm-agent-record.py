#!/usr/bin/env python3
"""Drive ONE OCR+LLM round trip through the JVM app while the GraalVM tracing
agent records (record-agent.sh), so the reflection usage of the full LLM chain
(OpenAiChatModel response processing, Jackson 3 convertValue, etc.) lands in
agent-config-llm/ and gets UNION-merged by gen-logger-config.py together with
the committed boot-path recording in agent-config/.

Local-only helper: not committed (hardcodes container name / mock port).
"""
import json
import os
import socket
import subprocess
import sys
import time

WINDOW = '240'  # covers mvnw classpath gen + JVM boot + 120s curl + margin

container = 'stock-calculator-service_postgres_1'
env = dict(os.environ)
env['AGENT_OUT'] = 'agent-config-llm'
# AI 端点（groq/azure/google）必须走代理；127.0.0.1 mock 被 JDK 默认
# nonProxyHosts 绕开，不受影响
env['JAVA_OPTS'] = ('-Dhttp.proxyHost=192.168.1.40 -Dhttp.proxyPort=2080 '
                    '-Dhttps.proxyHost=192.168.1.40 -Dhttps.proxyPort=2080')
if 'POSTGRES_PASS' not in env:
    out = subprocess.run(['podman', 'exec', container, 'env'],
                         capture_output=True, text=True, check=True).stdout
    pw = next((ln.split('=', 1)[1] for ln in out.splitlines()
               if ln.startswith('POSTGRES_PASSWORD=')), None)
    if not pw:
        print('no POSTGRES_PASSWORD in container env', file=sys.stderr)
        sys.exit(2)
    env['POSTGRES_PASS'] = pw

mock_log = open('/tmp/mock-record.log', 'w')
mock = subprocess.Popen(['python3', 'mock_openai_server.py'],
                        stdout=mock_log, stderr=subprocess.STDOUT)
time.sleep(0.6)

print('record-agent.sh starting (window', WINDOW, 's -> agent-config-llm) ...')
rec = subprocess.Popen(
    ['bash', 'record-agent.sh', WINDOW,
     '--llm.gemini.base-url=http://127.0.0.1:18080',
     '--llm.gemini.api-key=mock-key',
     '--llm.gemini.model=mock-model',
     # spring.ai.model.audio.speech 缺省即激活且要求凭证 -> 录制环境直接关掉；
     # 其余 spring.ai.openai 自动装配给个 dummy key 让 client 能构建（不联网）
     '--spring.ai.model.audio.speech=none',
     '--spring.ai.openai.api-key=mock-key'],
    env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)

ok = False
ready = False
try:
    for _ in range(200):
        if rec.poll() is not None:
            break
        try:
            s = socket.create_connection(('127.0.0.1', 19998), timeout=1)
            s.close()
            ready = True
            break
        except OSError:
            time.sleep(1.0)
    print('app tcp ready =', ready)
    if ready:
        time.sleep(3)
        out = subprocess.run(
            ['curl', '-s', '--max-time', '120', '-w', '\n%{http_code}',
             '-F', 'file=@/tmp/ni-test-image.jpg;type=image/jpeg',
             'http://127.0.0.1:19998/api/import/process-image?useCache=false'],
            capture_output=True, text=True, timeout=150)
        body_text, _, status = out.stdout.rpartition('\n')
        print('HTTP', status, '->', body_text[:300])
        try:
            body = json.loads(body_text)
            ok = status == '200' and body.get('code') == 200
        except Exception:
            ok = False
    print('waiting for record-agent.sh SIGTERM + agent flush ...')
    so, _ = rec.communicate(timeout=420)
    print(so[-1200:])
finally:
    if rec.poll() is None:
        rec.kill()
    mock.terminate()
    try:
        mock.wait(timeout=5)
    except Exception:
        mock.kill()
    mock_log.close()

exists = os.path.exists('agent-config-llm/reachability-metadata.json')
print('---')
print('recorded =', exists)
print('call_ok =', ok)
print('mock log:', open('/tmp/mock-record.log').read()[:300])
