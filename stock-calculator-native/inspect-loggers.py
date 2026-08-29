#!/usr/bin/env python3
"""Inspect jboss-logging generated logger classes in the native classpath jars."""
import zipfile, re, subprocess, os, sys

os.chdir(os.path.dirname(os.path.abspath(__file__)))

cp_path = 'target/cp.txt'
jars = [j for j in open(cp_path).read().strip().split(':') if j.endswith('.jar')]

logger_classes = {}   # fqcn -> jar path
bundles = []          # (jar, resource)
for j in jars:
    try:
        z = zipfile.ZipFile(j)
    except Exception:
        continue
    for n in z.namelist():
        if n.endswith('_$logger.class'):
            logger_classes[n[:-6].replace('/', '.')] = j
        if '.i18n.properties' in n:
            bundles.append((os.path.basename(j), n))

by_jar = {}
for fq, j in logger_classes.items():
    by_jar.setdefault(os.path.basename(j), []).append(fq)
print('=== _$logger classes per jar ===')
for j, lst in sorted(by_jar.items()):
    print(f'{j}: {len(lst)}')
print('TOTAL:', len(logger_classes))

hib = [j for j in jars if 'hibernate-core' in j][0]
z = zipfile.ZipFile(hib)

# --- JpaLogger_$logger: constructors + embedded strings ---
data = z.read('org/hibernate/jpa/internal/JpaLogger_$logger.class')
with open('/tmp/JpaLoggerGen.class', 'wb') as f:
    f.write(data)
out = subprocess.run(['javap', '-p', '/tmp/JpaLoggerGen.class'],
                     capture_output=True, text=True).stdout
print('\n=== JpaLogger_$logger members ===')
for line in out.splitlines():
    if 'JpaLogger_$logger(' in line or 'class ' in line:
        print(line.strip())

strs = [s.decode('ascii', 'ignore') for s in re.findall(rb'[ -~]{10,}', data)]
msg = [s for s in strs if '%' in s or 'HHH' in s or 'Unable' in s or 'Persistence' in s]
print('\n=== embedded message strings (sample) ===')
for s in msg[:6]:
    print(' ', s[:100])
print('embedded-string count:', len(msg))

# --- bundle resources present? ---
print('\n=== JpaLogger i18n bundles in hibernate-core ===')
for n in z.namelist():
    if 'JpaLogger' in n and n.endswith('.properties'):
        print(' ', n)

# --- jboss-logging Messages: how is msg initialized? ---
jl = [j for j in jars if 'jboss-logging-3' in j][0]
zd = zipfile.ZipFile(jl).read('org/jboss/logging/Messages.class')
with open('/tmp/JBossMessages.class', 'wb') as f:
    f.write(zd)
out = subprocess.run(['javap', '-p', '-c', '/tmp/JBossMessages.class'],
                     capture_output=True, text=True).stdout
lines = out.splitlines()
print('\n=== Messages: bundle init calls ===')
for l in lines:
    if 'getBundle' in l or 'getMessageLogger' in l or 'MethodHandles' in l or 'findClass' in l:
        print(l.strip())
print('has-clinit:', any('static' in l and '{}' in l for l in lines))
