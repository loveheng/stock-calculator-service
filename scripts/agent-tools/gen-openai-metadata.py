#!/usr/bin/env python3
"""openai SDK 反射元数据生成器（从 gen-native-metadata.py 拆出的独立脚本，D2）。

背景（docs：stock-calculator-native-runtime-metadata skill §十一）：
openai-java + Spring AI 有两段 Jackson 反射面，jar 自带的 classic 元数据只覆盖
构造器/字段/getter，覆盖不到——
  1. 反序列化：响应含 SDK 未建模字段时，Jackson any-setter 反射调用
     private putAdditionalProperty(String, com.openai.core.JsonValue)；
     Gemini/Groq 兼容网关响应必带未建模字段 → 必崩（MissingReflectionRegistrationError，
     是 Error 不是 Exception，所有 catch(Exception) 拦不住）。
  2. 序列化：OpenAiChatModel.from() 把 ChatCompletion._additionalProperties() 经
     Jackson 3（tools.jackson）convertValue，MethodHandle 链反射调 JsonField.isMissing()。
修法 = 包级全量注册（拒绝逐方法打地鼠）：字节码含 putAdditionalProperty 常量的类
显式注册 any-setter 签名（对仅引用常量的类静默容忍）+ com.openai.core.** 全部类
注册全部声明方法（纯 Python class 文件解析器，descriptor 转点分参数类型）。

与 JPA/hibernate/jboss-logging 无关，故独立成共享脚本，供所有用到 Spring AI OpenAI
的模块（main/mcp/mcp-notify/orchestration/data）统一调用，消除每模块各持一份的漂移。

产物：target/classes/META-INF/native-image/com.zzh/ni-openai-config/
reachability-metadata.json（native-image 自动检测 classpath 目录元数据）。
构建入口：build-native.sh 步骤 2.5（读 target/cp.txt，须在该步之后执行）。
"""
import json
import os
import struct
import sys
import zipfile

# 共享生成器：由各模块 build-native.sh 从模块目录调用（脚本开头已
# `cd "$(dirname "$0")"`），故 cwd 即模块目录；CI/非 cwd 场景可传模块目录为 argv[1]。
MODULE_DIR = sys.argv[1] if len(sys.argv) > 1 else os.getcwd()
os.chdir(MODULE_DIR)

cp = 'target/cp.txt'
if not os.path.exists(cp):
    print('gen-openai-metadata: target/cp.txt not found (run the maven step first)', file=sys.stderr)
    sys.exit(1)

OPENAI_ANY_SETTER = {
    'name': 'putAdditionalProperty',
    'parameterTypes': ['java.lang.String', 'com.openai.core.JsonValue'],
}

def scan_openai_any_setter():
    # class bytecode carries the method-name constant whenever the class
    # DECLARES or references the any-setter; native-image silently tolerates
    # registrations for absent members, so no proper method-table parse needed
    found = set()
    for j in open(cp).read().strip().split(':'):
        j = j.strip()
        if not j.endswith('.jar') or not os.path.exists(j):
            continue
        if 'openai' not in os.path.basename(j).lower():
            continue
        try:
            z = zipfile.ZipFile(j)
        except Exception:
            continue
        for n in z.namelist():
            if n.endswith('.class') and b'putAdditionalProperty' in z.read(n):
                found.add(n[:-6].replace('/', '.'))
    return found

PRIM_TYPES = {'B': 'byte', 'C': 'char', 'D': 'double', 'F': 'float',
              'I': 'int', 'J': 'long', 'S': 'short', 'Z': 'boolean'}

def descriptor_param_types(desc):
    # '(Ljava/lang/String;J[[I)V' -> ['java.lang.String', 'long', 'int[][]']
    end = desc.find(')')
    if end < 0:
        raise ValueError('bad method descriptor')
    body = desc[1:end]
    out = []
    k = 0
    while k < len(body):
        dims = 0
        while k < len(body) and body[k] == '[':
            dims += 1
            k += 1
        c = body[k]
        k += 1
        if c == 'L':
            semi = body.find(';', k)
            if semi < 0:
                raise ValueError('bad method descriptor')
            base = body[k:semi].replace('/', '.')
            k = semi + 1
        else:
            base = PRIM_TYPES[c]
        out.append(base + '[]' * dims)
    return out

def parse_class_methods(data):
    """Declared methods of a class file as (name, (param types...)) tuples.

    Minimal JVM spec walk: magic+version, constant pool (long/double take two
    slots), flags/this/super, interfaces, then the fields and methods tables
    (skipping each member's attributes). Enough for name+descriptor extraction;
    any surprise raises and the caller skips that class.
    """
    def u2(off):
        return struct.unpack_from('>H', data, off)[0]

    pos = 8
    cp_count = u2(pos)
    pos += 2
    utf8 = {}
    idx = 1
    while idx < cp_count:
        tag = data[pos]
        pos += 1
        if tag == 1:
            ln = u2(pos)
            pos += 2
            utf8[idx] = data[pos:pos + ln]
            pos += ln
        elif tag in (7, 8, 16, 19, 20):
            pos += 2
        elif tag == 15:
            pos += 3
        elif tag in (3, 4, 9, 10, 11, 12, 17, 18):
            pos += 4
        elif tag in (5, 6):
            pos += 8
            idx += 1
        else:
            raise ValueError('unknown constant pool tag ' + str(tag))
        idx += 1
    pos += 6  # access_flags + this_class + super_class
    pos += 2 + 2 * u2(pos)  # interfaces
    methods = []
    for section in (0, 1):  # 0 = fields (parsed, discarded), 1 = methods
        count = u2(pos)
        pos += 2
        collected = []
        for _i in range(count):
            name_i, desc_i, attr_n = u2(pos + 2), u2(pos + 4), u2(pos + 6)
            pos += 8
            for _a in range(attr_n):
                alen = struct.unpack_from('>I', data, pos + 2)[0]
                pos += 6 + alen
            if section != 1:
                continue
            name = utf8.get(name_i, b'').decode('utf-8', 'replace')
            if name == '<clinit>':
                continue
            try:
                params = descriptor_param_types(
                    utf8.get(desc_i, b'').decode('utf-8', 'replace'))
            except (ValueError, KeyError, IndexError):
                continue
            collected.append((name, tuple(params)))
        if section == 1:
            methods = collected
    return methods

def scan_openai_core_methods():
    # all declared methods of every class under com/openai/core/ in every
    # openai jar on the classpath -> explicit invocable registration
    found = {}
    for j in open(cp).read().strip().split(':'):
        j = j.strip()
        if not j.endswith('.jar') or not os.path.exists(j):
            continue
        if 'openai' not in os.path.basename(j).lower():
            continue
        try:
            z = zipfile.ZipFile(j)
        except Exception:
            continue
        for n in z.namelist():
            if not n.endswith('.class'):
                continue
            fqn = n[:-6].replace('/', '.')
            if not fqn.startswith('com.openai.core.'):
                continue
            try:
                ms = parse_class_methods(z.read(n))
            except Exception:
                continue
            if ms:
                found.setdefault(fqn, set()).update(ms)
    return found

def main():
    any_setter = scan_openai_any_setter()
    core_methods = scan_openai_core_methods()
    reflection = []
    for fq in sorted(any_setter):
        reflection.append({'type': fq, 'methods': [dict(OPENAI_ANY_SETTER)]})
    method_count = 0
    for fq in sorted(core_methods):
        entry = {'type': fq, 'methods': []}
        for name, params in sorted(core_methods[fq]):
            entry['methods'].append({'name': name, 'parameterTypes': list(params)})
            method_count += 1
        reflection.append(entry)
    out_dir = os.path.join('target', 'classes',
                           'META-INF', 'native-image', 'com.zzh', 'ni-openai-config')
    os.makedirs(out_dir, exist_ok=True)
    with open(os.path.join(out_dir, 'reachability-metadata.json'), 'w') as f:
        json.dump({'reflection': reflection, 'resources': []}, f, indent=2)
    print(f'gen-openai-metadata: registered {len(reflection)} reflection entries '
          f'({len(any_setter)} any-setter classes, '
          f'{len(core_methods)} openai core classes / +{method_count} methods)')

if __name__ == '__main__':
    main()
