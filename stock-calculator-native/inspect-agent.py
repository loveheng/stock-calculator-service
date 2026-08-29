#!/usr/bin/env python3
"""Summarize what the tracing agent captured."""
import json, os
os.chdir(os.path.dirname(os.path.abspath(__file__)))
d = json.load(open('target/agent-config/reachability-metadata.json'))
print('sections:', {k: len(v) for k, v in d.items()})

def type_name(e):
    t = e.get('type') or e.get('name') or {}
    return t.get('name') if isinstance(t, dict) else t

names = [type_name(e) for e in d.get('reflection', [])]
names = [n for n in names if n]
for key in ('postgres', 'jboss', 'naming', 'ColumnOrdering', 'Dialect', 'hikari', 'zzh'):
    hits = [n for n in names if key.lower() in n.lower()]
    print(f'{key}: {len(hits)}', hits[:5])

res = [e.get('pattern', '') for e in d.get('resources', [])]
print('resource samples:', [p for p in res if 'postgres' in p or 'hibernate' in p][:6])
