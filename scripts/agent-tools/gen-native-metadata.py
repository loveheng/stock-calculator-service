#!/usr/bin/env python3
"""Register hibernate/jboss-logging dynamic classes for the native image.

Two classes of problems, one root cause: classes loaded only by computed name
strings are invisible to static reachability analysis and missing reflection
registration breaks runtime instantiation.

1. jboss-logging generated message loggers (XxxLogger_$logger): loaded at runtime
   via MethodHandles.Lookup.findClass with a computed class name. Without
   registration startup fails with:
       Invalid logger interface Xxx (implementation not found)

2. Strategies resolved by NAME at runtime (hibernate StrategySelectorBuilder
   registrations + spring.jpa.hibernate.naming.physical-strategy from
   application.yml): resolved via Class.forName then instantiated via
   getDeclaredConstructor(). Without ctor registration:
       Could not instantiate named strategy class [...]

Hibernate DTD/XSD schema resources are NO LONGER emitted by this script — they are
owned by the stock-calculator-jpa shared library (library resource-config.json +
JpaRuntimeHints resource hints), so every JPA consumer inherits them simply by
depending on that library. This script now only covers the dynamic, classpath-
dependent gaps (jboss-logging generated loggers, JPA annotation internals, agent
recorded resources). See docs/architecture/jpa-native-extraction.md (P1/P2).

3. Hibernate models 的 JPA 注解内部类（XxxJpaAnnotation）在运行期经反射构造，
   静态分析不可达；agent 只录得启动路径出现过的注解。实体新增注解（如 @Enumerated）
   就会 NoSuchMethodException。此处按 classpath 上实际存在的全部 JpaAnnotation
   实现类做 UNION 补齐（构造器签名统一为 (jakarta 注解, ModelsContext)），
   新实体加注解无需再重录 agent。

openai SDK 的反射面（any-setter + com.openai.core 全量方法）与本脚本无关，
已拆为独立脚本 gen-openai-metadata.py（D2），由各模块 build-native.sh 另调。

Scans every jar on the native classpath (target/cp.txt), registers all found
generated logger classes plus EXTRA_CLASSES (filtered to classes that actually
exist on the classpath, so it survives hibernate version changes). Output is a
single reachability-metadata.json (new consolidated format, same as the tracing
agent emits) written to target/classes/META-INF/native-image/, where native-image
auto-detects it. Existing entries from other config dirs (e.g. agent capture) are
merged when native-image runs, not here.

（静态可固化层——EXTRA_CLASSES 中的框架类——正逐步收口进 stock-calculator-jpa
共享库；本脚本保留模块特定 EXTRA_CLASSES 与必须扫 classpath 的动态段。）
"""
import json
import os
import sys
import zipfile

EXTRA_CLASSES = [
    # naming strategies (yml configures physical-strategy by name)
    'org.hibernate.boot.model.naming.PhysicalNamingStrategyStandardImpl',
    'org.hibernate.boot.model.naming.PhysicalNamingStrategySnakeCaseImpl',
    'org.hibernate.boot.model.naming.ImplicitNamingStrategyJpaCompliantImpl',
    'org.hibernate.boot.model.naming.ImplicitNamingStrategyLegacyJpaImpl',
    'org.hibernate.boot.model.naming.ImplicitNamingStrategyLegacyHbmImpl',
    'org.hibernate.boot.model.naming.ImplicitNamingStrategyComponentPathImpl',
    # named strategies registered by hibernate 7.x StrategySelectorBuilder,
    # instantiated reflectively at runtime when selected
    'org.hibernate.boot.model.relational.ColumnOrderingStrategyStandard',
    'org.hibernate.boot.model.relational.ColumnOrderingStrategyLegacy',
    'org.hibernate.resource.transaction.backend.jdbc.internal.JdbcResourceLocalTransactionCoordinatorBuilderImpl',
    'org.hibernate.resource.transaction.backend.jta.internal.JtaTransactionCoordinatorBuilderImpl',
    'org.hibernate.query.sqm.mutation.internal.cte.CteInsertStrategy',
    'org.hibernate.query.sqm.mutation.internal.temptable.GlobalTemporaryTableInsertStrategy',
    'org.hibernate.query.sqm.mutation.internal.temptable.LocalTemporaryTableInsertStrategy',
    'org.hibernate.query.sqm.mutation.internal.temptable.PersistentTableInsertStrategy',
    'org.hibernate.query.sqm.mutation.internal.cte.CteMutationStrategy',
    'org.hibernate.query.sqm.mutation.internal.temptable.GlobalTemporaryTableMutationStrategy',
    'org.hibernate.query.sqm.mutation.internal.temptable.LocalTemporaryTableMutationStrategy',
    'org.hibernate.query.sqm.mutation.internal.temptable.PersistentTableMutationStrategy',
    'org.hibernate.id.enhanced.StandardNamingStrategy',
    'org.hibernate.id.enhanced.SingleNamingStrategy',
    'org.hibernate.id.enhanced.LegacyNamingStrategy',
    'org.hibernate.cache.internal.DefaultCacheKeysFactory',
    'org.hibernate.cache.internal.SimpleCacheKeysFactory',
    'org.hibernate.type.format.jackson.JacksonJsonFormatMapper',
    'org.hibernate.type.format.jackson.Jackson3JsonFormatMapper',
    'org.hibernate.type.format.jackson.JacksonXmlFormatMapper',
    'org.hibernate.type.format.jaxb.JaxbXmlFormatMapper',
    # dialect resolved by name from JDBC metadata
    'org.hibernate.dialect.PostgreSQLDialect',
    # PgJdbcHelper 按 JDBC type name 实例化的 PG 专用 JdbcType 全族（静态不可达，
    # 冒烟逐个暴露过 Inet/IntervalSecond，一次注册全族防打地鼠）
    'org.hibernate.dialect.type.PostgreSQLArrayJdbcType',
    'org.hibernate.dialect.type.PostgreSQLArrayJdbcTypeConstructor',
    'org.hibernate.dialect.type.PostgreSQLCastingInetJdbcType',
    'org.hibernate.dialect.type.PostgreSQLCastingIntervalSecondJdbcType',
    'org.hibernate.dialect.type.PostgreSQLCastingJsonArrayJdbcType',
    'org.hibernate.dialect.type.PostgreSQLCastingJsonArrayJdbcTypeConstructor',
    'org.hibernate.dialect.type.PostgreSQLCastingJsonJdbcType',
    'org.hibernate.dialect.type.PostgreSQLEnumJdbcType',
    'org.hibernate.dialect.type.PostgreSQLInetJdbcType',
    'org.hibernate.dialect.type.PostgreSQLIntervalSecondJdbcType',
    'org.hibernate.dialect.type.PostgreSQLJsonArrayPGObjectJsonJdbcTypeConstructor',
    'org.hibernate.dialect.type.PostgreSQLJsonArrayPGObjectJsonbJdbcTypeConstructor',
    'org.hibernate.dialect.type.PostgreSQLJsonArrayPGObjectType',
    'org.hibernate.dialect.type.PostgreSQLJsonPGObjectJsonType',
    'org.hibernate.dialect.type.PostgreSQLJsonPGObjectJsonbType',
    'org.hibernate.dialect.type.PostgreSQLOrdinalEnumJdbcType',
    'org.hibernate.dialect.type.PostgreSQLStructCastingJdbcType',
    'org.hibernate.dialect.type.PostgreSQLStructPGObjectJdbcType',
    'org.hibernate.dialect.type.PostgreSQLUUIDJdbcType',
    # id optimizers (used when entities map sequences)
    # hibernate 注解模型运行期按名实例化的注解内部类（静态不可达，冒烟实测
    #   NoSuchMethodException: CacheAnnotation.<init>(ModelsContext)）
    'org.hibernate.boot.models.annotations.internal.CacheAnnotation',
    'org.hibernate.id.enhanced.NoopOptimizer',
    'org.hibernate.id.enhanced.PooledOptimizer',
    'org.hibernate.id.enhanced.PooledLoOptimizer',
    'org.hibernate.id.enhanced.HiLoOptimizer',
    'org.hibernate.id.enhanced.LegacyHiLoAlgorithmOptimizer',
    # hikari ConcurrentBag allocates via Array.newInstance, which the tracing
    # agent does NOT record (known gap) and native requires registration for
    'com.zaxxer.hikari.util.ConcurrentBag$IConcurrentBagEntry',
    'com.zaxxer.hikari.util.ConcurrentBag$IConcurrentBagEntry[]',
    # EntityBatchLoaderArrayParam (default_batch_fetch_size=16) reflectively
    # allocates id arrays per entity id type; the tracing agent does NOT record
    # Array.newInstance targets, and the committed recording predates the
    # UUID-id auth entities (User/UserProfile/AuthSession), hence:
    #   Cannot reflectively instantiate the array class 'java.util.UUID[]'
    'java.util.UUID[]',
    'java.lang.Long[]',
    'java.lang.String[]',
    # hikari PoolEntry 构造期经 ClockSource/ProxyFactory 反射实例化 JDBC 数组类
    #（tracing agent 盲区，native 运行期建连即崩：
    #   MissingReflectionRegistrationError: Cannot reflectively instantiate
    #   the array class 'java.sql.Statement[]'）
    'java.sql.Statement[]',
    'java.sql.ResultSet[]',
    'java.sql.Connection[]',
    # hibernate bytecode provider, selected via ServiceLoader in hibernate 7.x
    # (BytecodeProviderInitiator ignores hibernate.bytecode.provider settings):
    #   empty service discovery -> built-in none provider (DisallowedProxyFactory,
    #   whose postInstantiate is a no-op and getProxy throws)
    #   bytebuddy provider      -> ByteBuddyProxyFactory defines proxy classes at
    #   runtime via ClassInjector -> always crashes on native image
    # We therefore EXCLUDE the service file below (RESOURCE_EXCLUDES) so discovery
    # is empty and hibernate falls back to the none provider; the none provider
    # ctor is still registered defensively in case some path instantiates it
    'org.hibernate.bytecode.internal.none.BytecodeProviderImpl',
    'org.hibernate.boot.registry.selector.internal.StrategySelectorImpl',
    # StockDictMemoryService 字典镜像 readValue(StockDictEntry)：Jackson 3 走
    # PropertyBasedCreator 反射调无参 ctor；Spring AOT(@RegisterReflectionForBinding)
    # 只显式注册 getter/setter，构造器依赖 allDeclaredConstructors——新版元数据
    # 静默忽略该键（技能四节），缺显式 <init> 即每行 "no property-based Creator"
    # 模块特定类：仅 mcp 模块 target/classes 含此类，其余模块经 extra_on_classpath
    # 过滤静默跳过（不进 stock-calculator-jpa 共享库）
    'com.zzh.stock_calculator.mcp.dict.StockDictEntry',
]

# 共享生成器：由各模块 build-native.sh 从模块目录调用（脚本开头已
# `cd "$(dirname "$0")"`），故 cwd 即模块目录；CI/非 cwd 场景可传模块目录为 argv[1]。
# 切勿 chdir 到脚本自身所在目录——target/cp.txt、target/classes、agent-config 都在模块目录。
MODULE_DIR = sys.argv[1] if len(sys.argv) > 1 else os.getcwd()
os.chdir(MODULE_DIR)

cp = 'target/cp.txt'
if not os.path.exists(cp):
    print('gen-native-metadata: target/cp.txt not found (run the maven step first)', file=sys.stderr)
    sys.exit(1)

all_names = set()
logger_classes = []
service_providers = set()   # all ServiceLoader providers across the classpath
for j in open(cp).read().strip().split(':'):
    j = j.strip()
    if not j.endswith('.jar') or not os.path.exists(j):
        continue
    try:
        z = zipfile.ZipFile(j)
    except Exception:
        continue
    for n in z.namelist():
        if n.endswith('.class'):
            all_names.add(n[:-6].replace('/', '.'))
            if n.endswith('_$logger.class'):
                logger_classes.append(n[:-6].replace('/', '.'))
        elif n.startswith('META-INF/services/'):
            for line in z.read(n).decode('utf-8', 'ignore').splitlines():
                line = line.strip()
                if line and not line.startswith('#'):
                    service_providers.add(line)

# 模块自身类编译在 target/classes（不在任何依赖 jar 里，cp.txt 只有依赖），
# 但同样运行在 native classpath 上——不扫这里的话 EXTRA_CLASSES 里引用本模块类
# 会被 extra_on_classpath 过滤静默跳过（2026-09 StockDictEntry 即中招）
own_classes = 'target/classes'
if os.path.isdir(own_classes):
    for root, _, files in os.walk(own_classes):
        for f in files:
            if f.endswith('.class'):
                rel = os.path.relpath(os.path.join(root, f), own_classes)
                all_names.add(rel[:-6].replace(os.sep, '.'))

def extra_on_classpath(c):
    # array types like 'Foo[]' exist only through their component class
    comp = c[:-2] if c.endswith('[]') else c
    # JDK-resolvable types never appear in the jar-scanned class names
    if comp.startswith(('java.', 'javax.', 'sun.', 'com.sun.', 'jdk.')):
        return True
    return comp in all_names

present = [c for c in EXTRA_CLASSES if extra_on_classpath(c)]
missing = [c for c in EXTRA_CLASSES if not extra_on_classpath(c)]
if missing:
    print(f'gen-native-metadata: {len(missing)} extra classes not on classpath, skipped')

classes = sorted(set(present) | set(logger_classes) | (service_providers & all_names))

# merge tracing-agent metadata so a single consolidated file is produced.
# agent-config/ (committed to the repo) is preferred; target/agent-config/ is
# a legacy local-only location. WITHOUT this file CI builds would only get the
# generator's ~132 reflection entries — the ~1683 agent-recorded entries cover
# runtime gaps (hikari internals, jdbc driver wiring, …) that are invisible to
# static analysis and not derivable from the classpath. It is committed on
# purpose (like the graalvm reachability-metadata project does); re-record with
# native-image-agent when hibernate/dependency upgrades change the boot path.
agent_meta = {'reflection': [], 'resources': []}
# 'agent-config-llm' holds an optional recording of a full OCR+LLM round trip
# (mock OpenAI server, see record-agent.sh AGENT_OUT); merged as a UNION with
# the committed boot-path recording so neither can clobber the other.
agent_dirs = [d for d in ('agent-config-llm', 'agent-config', 'target/agent-config')
              if os.path.exists(os.path.join(d, 'reachability-metadata.json'))]
for d in agent_dirs:
    m = json.load(open(os.path.join(d, 'reachability-metadata.json')))
    agent_meta['reflection'].extend(m.get('reflection', []))
    agent_meta['resources'].extend(m.get('resources', []))

def fill_missing_jpa_annotation_reflection(entries):
    known = set()
    for e in entries:
        t = e.get('type')
        known.add(t.get('name') if isinstance(t, dict) else t)
    found = []
    for j in open(cp).read().strip().split(':'):
        j = j.strip()
        if not j.endswith('.jar') or not os.path.exists(j):
            continue
        try:
            z = zipfile.ZipFile(j)
        except Exception:
            continue
        for n in z.namelist():
            if not n.startswith('org/hibernate/boot/models/annotations/internal/'):
                continue
            if not n.endswith('JpaAnnotation.class'):
                continue
            fq = n[:-6].replace('/', '.')
            if fq in known:
                continue
            ann = fq.rsplit('.', 1)[-1].replace('JpaAnnotation', '')
            entries.append({
                'type': fq,
                'methods': [{
                    'name': '<init>',
                    'parameterTypes': [
                        'jakarta.persistence.' + ann,
                        'org.hibernate.models.spi.ModelsContext',
                    ],
                }],
            })
            found.append(fq)
    return found

fill_missing_jpa_annotation_reflection(agent_meta['reflection'])

def type_of(entry):
    t = entry.get('type')
    return t.get('name') if isinstance(t, dict) else t

def entry_methods(e):
    return e.setdefault('methods', []) if isinstance(e, dict) else []

def union_members(dst, src):
    # two agent recordings may both contain a type: union methods (full
    # signature) and fields so neither side loses registrations
    sm = src.get('methods')
    if sm:
        dm = dst.setdefault('methods', [])
        have = {(m.get('name'), tuple(m.get('parameterTypes', []))) for m in dm}
        for m in sm:
            sig = (m.get('name'), tuple(m.get('parameterTypes', [])))
            if sig not in have:
                dm.append(m)
                have.add(sig)
    sf = src.get('fields')
    if sf:
        df = dst.setdefault('fields', [])
        havef = {tuple(sorted(f.items())) for f in df}
        for f in sf:
            key = tuple(sorted(f.items()))
            if key not in havef:
                df.append(f)
                havef.add(key)

existing = {}
merged = []
for e in agent_meta.get('reflection', []):
    t = type_of(e)
    seen = existing.get(t)
    if seen is None:
        existing[t] = e
        merged.append(e)
    else:
        union_members(seen, e)
logger_set = set(logger_classes)

# jboss-logging generated logger classes have exactly one constructor, taking
# org.jboss.logging.Logger (javap-verified); jboss-logging's getMessageLogger
# instantiates them reflectively via that constructor. The no-arg entry is kept
# because native-image silently tolerates registrations for absent members.
# EXTRA_CTORS: additional constructor signatures invoked reflectively by name
# (e.g. hibernate models CacheAnnotation.<init>(ModelsContext), smoke-verified)
EXTRA_CTORS = {
    'org.hibernate.boot.models.annotations.internal.CacheAnnotation':
        [['org.hibernate.models.spi.ModelsContext']],
    # SessionFactoryOptionsBuilder.lambda$formatMapper 对显式按名选择的 json/xml
    # format mapper（hibernate.type.json_format_mapper 等设置）优先反射调用
    # <init>(FormatMapperCreationContext)，缺失才回退无参（javap 实证 7.4.5.Final）。
    # 不注册则 json_format_mapper=jackson3 时 native EMF 构建即炸
    # （Could not instantiate named strategy class [..Jackson3JsonFormatMapper]）。
    # JaxbXmlFormatMapper 无该构造器，走无参回退，无需登记
    'org.hibernate.type.format.jackson.Jackson3JsonFormatMapper':
        [['org.hibernate.type.format.FormatMapperCreationContext']],
    'org.hibernate.type.format.jackson.JacksonJsonFormatMapper':
        [['org.hibernate.type.format.FormatMapperCreationContext']],
    'org.hibernate.type.format.jackson.JacksonXmlFormatMapper':
        [['org.hibernate.type.format.FormatMapperCreationContext']],
}

def ctor_entries(fq):
    ctors = [{'name': '<init>', 'parameterTypes': []}]
    if fq in logger_set:
        ctors.append({'name': '<init>', 'parameterTypes': ['org.jboss.logging.Logger']})
    for params in EXTRA_CTORS.get(fq, []):
        ctors.append({'name': '<init>', 'parameterTypes': params})
    return ctors

for fq in classes:
    if fq.endswith('[]'):
        entry = {'type': fq}
    else:
        # the new consolidated format requires explicit method entries for
        # INVOCATION (query* flags only allow introspection); constructors are
        # what ServiceLoader.newInstance, StrategySelector.create and
        # jboss-logging getMessageLogger call
        entry = {
            'type': fq,
            'queryAllDeclaredMethods': True,
            'queryAllPublicMethods': True,
            'queryAllDeclaredConstructors': True,
            'queryAllPublicConstructors': True,
            'queryAllDeclaredFields': True,
            'methods': ctor_entries(fq),
        }
    seen = existing.get(fq)
    if seen is None:
        merged.append(entry)
    else:
        # agent captured the type but may lack invokable constructors: add any
        # missing ones (full-signature aware; e.g. a logger class the agent saw
        # without the (Logger) ctor would still fail at runtime)
        methods = seen.setdefault('methods', [])
        have = {(m.get('name'), tuple(m.get('parameterTypes', []))) for m in methods}
        for c in ctor_entries(fq):
            if (c['name'], tuple(c['parameterTypes'])) not in have:
                methods.append(c)

# DTD/XSD schema resources（org/hibernate、jakarta/persistence）已收口进
# stock-calculator-jpa 共享库：库自带 resource-config.json（经典 includes）+
# JpaRuntimeHints(RuntimeHints.resource) 双轨提供，消费模块依赖即自动继承。
# 本脚本不再重复产出 DTD；仅合并 agent 录制资源（jdbc 驱动等运行期缺口）。
# （openai 段无 DTD 需求，见 gen-openai-metadata.py；BytecodeProvider service
#  排除也已在库的 resource-config.json 表达，无需脚本兜底）
merged_res = list(agent_meta.get('resources', []))

out_dir = 'target/classes/META-INF/native-image/com.zzh/ni-logger-config'
os.makedirs(out_dir, exist_ok=True)
with open(os.path.join(out_dir, 'reachability-metadata.json'), 'w') as f:
    json.dump({'reflection': merged, 'resources': merged_res}, f, indent=2)
print(f'gen-native-metadata: agent dirs merged: {agent_dirs or "(none)"}')
print(f'gen-native-metadata: registered {len(merged)} reflection entries '
      f'(+{len(merged) - len(agent_meta.get("reflection", []))} from generator) and '
      f'{len(merged_res)} resource patterns; BytecodeProvider service excluded '
      f'(openai 段见 gen-openai-metadata.py)')
