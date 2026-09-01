#!/usr/bin/env python3
"""End-to-end validation against the REAL Gemini OpenAI-compatible endpoint,
reaching it through the user's HTTP proxy. The real gateway response carries
whatever unmodeled fields production saw, so this is the faithful reproduction
of the original MissingReflectionRegistrationError crash.
"""
import json
import os
import socket
import subprocess
import sys
import time

container = 'stock-calculator-service_postgres_1'
env = dict(os.environ)
if 'POSTGRES_PASS' not in env:
    out = subprocess.run(['podman', 'exec', container, 'env'],
                         capture_output=True, text=True, check=True).stdout
    pw = next((ln.split('=', 1)[1] for ln in out.splitlines()
               if ln.startswith('POSTGRES_PASSWORD=')), None)
    if not pw:
        print('no POSTGRES_PASSWORD in container env', file=sys.stderr)
        sys.exit(2)
    env['POSTGRES_PASS'] = pw
env['GEMINI_API_KEY'] = open('/tmp/gemini.key').read().strip()

log = open('/tmp/ni-realllm.log', 'w')
app = subprocess.Popen(
    ['./target/stock-calculator-service',
     '-Dhttp.proxyHost=192.168.1.40', '-Dhttp.proxyPort=2080',
     '-Dhttps.proxyHost=192.168.1.40', '-Dhttps.proxyPort=2080',
     '--server.port=19997', '--crawler.enabled=false'],
    env=env, stdout=log, stderr=subprocess.STDOUT)

ok = False
try:
    ready = False
    for _ in range(120):
        if app.poll() is not None:
            break
        try:
            s = socket.create_connection(('127.0.0.1', 19997), timeout=1)
            s.close()
            ready = True
            break
        except OSError:
            time.sleep(0.5)
    if not ready:
        raise RuntimeError('app did not become ready')

    out = subprocess.run(
        ['curl', '-s', '--max-time', '120', '-w', '\n%{http_code}',
         '-F', 'file=@/tmp/ni-test-image.jpg;type=image/jpeg',
         'http://127.0.0.1:19997/api/import/process-image?useCache=false'],
        capture_output=True, text=True, timeout=150)
    body_text, _, status = out.stdout.rpartition('\n')
    print('HTTP', status, '->', body_text[:400])
    try:
        body = json.loads(body_text)
        ok = status == '200' and body.get('code') == 200
    except Exception:
        ok = False
finally:
    app.terminate()
    try:
        app.wait(timeout=20)
    except subprocess.TimeoutExpired:
        app.kill()
    log.close()

text = open('/tmp/ni-realllm.log', encoding='utf-8', errors='ignore').read()
print('---')
print('started =', 'Started StockCalculatorApplication' in text)
print('drafts_ok =', ok)
print('reflection_error =', 'MissingReflectionRegistrationError' in text)
print('chain_done =', '全链路完成' in text)
errs = [ln for ln in text.splitlines() if ' ERROR ' in ln]
print('errors =', len(errs))
for ln in errs[:6]:
    print('  ', ln[:200])
print('--- 关键链路日志 ---')
for ln in text.splitlines():
    if any(k in ln for k in ('OCR 识别', 'OCR 渠道', 'LLM 渠道', 'LLM 责任链',
                             '全链路完成', 'reason=')):
        print('  ', ln[:230])
