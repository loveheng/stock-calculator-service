#!/usr/bin/env python3
"""Merge stock-calculator-common into stock-calculator-main (single module).
Moves domain sources (merging directories where the target already exists),
archives reorg-*.py scripts to repo root, then removes the common module dir.
Run once from repo root.
"""
import os, shutil

SRC = 'stock-calculator-common/src'
DST = 'stock-calculator-main/src'
PKG = 'com/zzh/stock_calculator'

def merge_move(src, dst):
    """Move contents of src into dst, merging existing dirs recursively."""
    os.makedirs(dst, exist_ok=True)
    for fn in os.listdir(src):
        s, d = os.path.join(src, fn), os.path.join(dst, fn)
        if os.path.isdir(s):
            merge_move(s, d)
        else:
            shutil.move(s, d)
    os.rmdir(src)

# 1) main sources: auth crawler common config util move directly; vision merges
for d in ('auth', 'crawler', 'common', 'config', 'util'):
    merge_move(f'{SRC}/main/java/{PKG}/{d}', f'{DST}/main/java/{PKG}/{d}')
    print('merged main/java/' + d)
merge_move(f'{SRC}/main/java/{PKG}/vision', f'{DST}/main/java/{PKG}/vision')
print('merged main/java/vision (with existing vision/impl)')

# 2) test sources: auth direct; crawler merges (main already has crawler/task)
merge_move(f'{SRC}/test/java/{PKG}/auth', f'{DST}/test/java/{PKG}/auth')
print('merged test/java/auth')
merge_move(f'{SRC}/test/java/{PKG}/crawler', f'{DST}/test/java/{PKG}/crawler')
print('merged test/java/crawler')

# 3) archive migration scripts to repo root
for f in ('reorg-packages.py', 'reorg-fixup.py', 'reorg-meta-fixup.py'):
    if os.path.exists(f'stock-calculator-common/{f}'):
        shutil.move(f'stock-calculator-common/{f}', f)
        print('archived', f)

# 4) remove the common module directory entirely
shutil.rmtree('stock-calculator-common')
print('removed stock-calculator-common/')

# 5) sanity: report leftover java files count in both trees
for label, root in (('main-sources', f'{DST}/main/java'), ('main-tests', f'{DST}/test/java')):
    n = sum(len(fns) for dp, _, fns in os.walk(root) if fns and fn.endswith('.java') for fn in fns) \
        if False else sum(1 for dp, _, fns in os.walk(root) for fn in fns if fn.endswith('.java'))
    print(label, '=', n)
