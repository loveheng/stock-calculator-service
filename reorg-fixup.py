#!/usr/bin/env python3
"""Fixups after reorg-packages.py:
1. move dto/auth/* -> auth/dto/* (missed by MOVES mapping)
2. rewrite package declarations from each file's directory path
   (reorg-packages.py only rewrote class-level FQNs, not `package x;` lines)
3. wildcard `import com.zzh.stock_calculator.dto.*;` -> crawler.entity.*
4. move main module's GeminiOcrExecutorImpl -> vision/impl
5. remove empty legacy dirs
Run once from repo root.
"""
import os, re, shutil

COMMON = 'stock-calculator-common/src'
MAIN = 'stock-calculator-main/src'
PKG = 'com/zzh/stock_calculator'

# 1) dto/auth -> auth/dto (main tree of common module)
src_dir = os.path.join(COMMON, 'main/java', PKG, 'dto/auth')
dst_dir = os.path.join(COMMON, 'main/java', PKG, 'auth/dto')
os.makedirs(dst_dir, exist_ok=True)
for fn in sorted(os.listdir(src_dir)):
    if fn.endswith('.java'):
        shutil.move(os.path.join(src_dir, fn), os.path.join(dst_dir, fn))
        print('moved dto/auth/' + fn, '-> auth/dto/' + fn)

# 4) main module: service/impl/GeminiOcrExecutorImpl -> vision/impl
src = os.path.join(MAIN, 'main/java', PKG, 'service/impl/GeminiOcrExecutorImpl.java')
if os.path.exists(src):
    dst = os.path.join(MAIN, 'main/java', PKG, 'vision/impl/GeminiOcrExecutorImpl.java')
    os.makedirs(os.path.dirname(dst), exist_ok=True)
    shutil.move(src, dst)
    print('moved main GeminiOcrExecutorImpl -> vision/impl')

# 2)+3) rewrite package decls + dto references in every java file
JAVA_ROOTS = [
    os.path.join(COMMON, 'main/java'), os.path.join(COMMON, 'test/java'),
    os.path.join(MAIN, 'main/java'), os.path.join(MAIN, 'test/java'),
]
pkg_re = re.compile(r'^package\s+[\w.]+;\s*$', re.M)
changed = 0
for root in JAVA_ROOTS:
    for dp, _, fns in os.walk(root):
        for fn in fns:
            if not fn.endswith('.java'):
                continue
            path = os.path.join(dp, fn)
            parts = os.path.relpath(path, root).split(os.sep)
            expected = '.'.join(parts[:-1])  # drop class name
            with open(path, encoding='utf-8') as fh:
                text = fh.read()
            orig = text
            text = text.replace('com.zzh.stock_calculator.dto.auth.',
                                'com.zzh.stock_calculator.auth.dto.')
            text = text.replace('import com.zzh.stock_calculator.dto.*;',
                                'import com.zzh.stock_calculator.crawler.entity.*;')
            text = pkg_re.sub('package ' + expected + ';', text, count=1)
            if text != orig:
                with open(path, 'w', encoding='utf-8') as fh:
                    fh.write(text)
                changed += 1
print('rewrote package/imports in', changed, 'files')

# 5) remove empty legacy dirs (parents after children)
LEGACY = []
for base, subs in ((COMMON, ['main/java', 'test/java']), (MAIN, ['main/java', 'test/java'])):
    for sub in subs:
        for d in ('service/impl', 'service', 'task', 'event', 'enums',
                  'dto/auth', 'dto', 'controller', 'entity', 'repository',
                  'config', 'util'):
            LEGACY.append(os.path.join(base, sub, PKG, d))
for d in LEGACY:
    if os.path.isdir(d):
        if os.listdir(d):
            print('NOT EMPTY, kept:', d, os.listdir(d))
        else:
            os.rmdir(d)
            print('rmdir', d)
