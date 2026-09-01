#!/usr/bin/env python3
"""End-to-end validation of the openai any-setter reflection fix inside the
native binary, with no external network or API keys:

1. start mock_openai_server.py (127.0.0.1:18080)
2. start target/stock-calculator-service with llm.gemini.* pointed at the mock
3. POST /api/import/process-image (multipart) -> OCR chain falls through to
   local-gemini (same @Primary geminiChatModel bean) -> mock; LLM chain
   (gemini channel) -> mock; both responses carry unmodeled fields
4. assert HTTP 200 + drafts, then SIGTERM and scan the log for reflection errors
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

# 1. mock server
mock_log = open('/tmp/mock.log', 'w')
mock = subprocess.Popen(['python3', 'mock_openai_server.py'],
                        stdout=mock_log, stderr=subprocess.STDOUT)
time.sleep(0.6)

# 2. native binary with gemini channel overridden to the mock
log = open('/tmp/ni-llmtest.log', 'w')
app = subprocess.Popen(
    ['./target/stock-calculator-service', '--server.port=19997',
     '--llm.gemini.base-url=http://127.0.0.1:18080',
     '--llm.gemini.api-key=mock-key',
     '--llm.gemini.model=mock-model',
     '--crawler.enabled=false'],
    env=env, stdout=log, stderr=subprocess.STDOUT)

# 3. wait for TCP readiness, then drive the OCR+LLM full chain via curl
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
    print('curl stderr:', out.stderr[:200] if out.stderr else '(none)')
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
    mock.terminate()
    log.close()
    mock_log.close()

# 4. log analysis
text = open('/tmp/ni-llmtest.log', encoding='utf-8', errors='ignore').read()
print('---')
print('started =', 'Tomcat started' in text or 'Started StockCalculatorApplication' in text)
print('drafts_ok =', ok)
print('reflection_error =', 'MissingReflectionRegistrationError' in text)
print('chain_done =', '全链路完成' in text)
errs = [ln for ln in text.splitlines() if ' ERROR ' in ln]
print('errors =', len(errs))
for ln in errs[:6]:
    print('  ', ln[:200])
print('--- mock log ---')
print(open('/tmp/mock.log').read()[:500])
