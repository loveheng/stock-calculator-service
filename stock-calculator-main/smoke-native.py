#!/usr/bin/env python3
"""Run the native binary smoke/90s validation with POSTGRES_PASS resolved from
the local postgres container env (value is never printed or logged)."""
import os, subprocess, sys

mode = sys.argv[1] if len(sys.argv) > 1 else 'smoke'
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

port = '19999' if mode == 'smoke' else '19997'
secs = '8' if mode == 'smoke' else '90'
log = '/tmp/ni-run.log' if mode == 'smoke' else '/tmp/ni-run90.log'
with open(log, 'w') as f:
    subprocess.run(['timeout', '--signal=TERM', secs,
                    './target/stock-calculator-service', f'--server.port={port}'],
                   env=env, stdout=f, stderr=subprocess.STDOUT)

text = open(log, encoding='utf-8', errors='ignore').read()
started = ('Tomcat started' in text) or ('Started StockCalculatorApplication' in text)
errors = [ln for ln in text.splitlines() if ' ERROR ' in ln]
warns = [ln for ln in text.splitlines() if ' WARN ' in ln]
print(f'started={started}  errors={len(errors)}  warns={len(warns)}')
for ln in (errors + warns)[:8]:
    print(ln[:220])
