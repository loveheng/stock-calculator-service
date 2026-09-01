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

Also includes hibernate DTD/XSD schema resources (LocalXmlResourceResolver
static-init resolves them; native images exclude resources by default):
   XmlInfrastructureException: Unable to locate schema [...] via classpath

3. openai-java (the Spring AI 2.x OpenAI HTTP client) ships agent-recorded
   metadata covering introspection (queryAllDeclaredMethods), fields and the
   constructors/getters its own recordings exercised — but NOT the private
   Jackson any-setter putAdditionalProperty(String, JsonValue), which only
   fires when a response contains a field the SDK does not model:
       MissingReflectionRegistrationError: Cannot reflectively invoke method
       'private final void ...putAdditionalProperty(...)'
   Model classes are uniform generated code, so every openai jar's class files
   are scanned for the method-name constant and the any-setter is registered
   explicitly (registrations on classes that merely reference the constant are
   silently tolerated, same as the no-arg ctor entries below).

4. Same root cause one level later: Spring AI's OpenAiChatModel.from() converts
   ChatCompletion._additionalProperties() (Map<String, JsonValue>) through
   Jackson 3 (tools.jackson) convertValue, whose BeanPropertyWriter fetches
   methods from com.openai.core.JsonField (isMissing()) reflectively via
   MethodHandles — the lambda only catches Exception, and
   MissingReflectionRegistrationError is an Error, so it kills the request.
   Registering single methods here would just shift the crash to the next one,
   so every class under com.openai.core. (the JsonField/JsonValue family and
   friends, ~205 classes) gets ALL its declared methods registered as explicit
   invocable entries via a small class-file parser (constant pool + method
   table; no javap dependency, works in CI). Models classes are deliberately
   NOT blanket-registered: they deserialize via constructors (the jar's own
   classic-format metadata covers those) plus the any-setter scan, and their
   getters are called directly (no reflection), so they add nothing but size.

Scans every jar on the native classpath (target/cp.txt), registers all found
generated logger classes plus EXTRA_CLASSES (filtered to classes that actually
exist on the classpath, so it survives hibernate version changes). Output is a
single reachability-metadata.json (new consolidated format, same as the tracing
agent emits) written to target/classes/META-INF/native-image/, where native-image
auto-detects it. Existing entries from other config dirs (e.g. agent capture) are
merged when native-image runs, not here.
"""
import json, os, struct, sys, zipfile

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
    # id optimizers (used when entities map sequences)
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
]

BASE = os.path.dirname(os.path.abspath(__file__))
os.chdir(BASE)  # stock-calculator-main/

cp = 'target/cp.txt'
if not os.path.exists(cp):
    print('gen-logger-config: target/cp.txt not found (run the maven step first)', file=sys.stderr)
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

openai_any_setter = scan_openai_any_setter()

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

openai_core_methods = scan_openai_core_methods()

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
    print(f'gen-logger-config: {len(missing)} extra classes not on classpath, skipped')

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
def ctor_entries(fq):
    ctors = [{'name': '<init>', 'parameterTypes': []}]
    if fq in logger_set:
        ctors.append({'name': '<init>', 'parameterTypes': ['org.jboss.logging.Logger']})
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

# an explicit methods entry is what makes INVOCATION work (the SDK's embedded
# queryAllDeclared* flags only enable introspection); merge into whatever entry
# already exists (agent-recorded or generated above) so each type appears once
merged_by_type = {}
for e in merged:
    merged_by_type.setdefault(type_of(e), e)
sig = (OPENAI_ANY_SETTER['name'], tuple(OPENAI_ANY_SETTER['parameterTypes']))
for fq in sorted(openai_any_setter):
    e = merged_by_type.get(fq)
    if e is None:
        merged_by_type[fq] = {'type': fq, 'methods': [dict(OPENAI_ANY_SETTER)]}
        merged.append(merged_by_type[fq])
        continue
    methods = e.setdefault('methods', [])
    have = {(m.get('name'), tuple(m.get('parameterTypes', []))) for m in methods}
    if sig not in have:
        methods.append(dict(OPENAI_ANY_SETTER))

# blanket: every declared method of every com.openai.core class becomes an
# explicit invocable entry (query* flags alone only allow introspection).
# Deterministic superset of what any tracing-agent recording could capture for
# this package — provider responses with arbitrary unmodeled fields all funnel
# through JsonField/JsonValue serialization, so one broad registration beats
# whack-a-mole per reported method.
openai_method_count = 0
for fq in sorted(openai_core_methods):
    e = merged_by_type.get(fq)
    if e is None:
        e = {'type': fq, 'methods': []}
        merged_by_type[fq] = e
        merged.append(e)
    methods = e.setdefault('methods', [])
    have = {(m.get('name'), tuple(m.get('parameterTypes', []))) for m in methods}
    for name, params in sorted(openai_core_methods[fq]):
        if (name, params) not in have:
            methods.append({'name': name, 'parameterTypes': list(params)})
            have.add((name, params))
            openai_method_count += 1

res_patterns = {e.get('pattern') or e.get('glob') for e in agent_meta.get('resources', [])}
merged_res = list(agent_meta.get('resources', []))
# NOTE: must use the 'glob' key — GraalVM 25 silently ignores 'pattern' entries in
# the consolidated reachability-metadata.json resources section (agent capture
# emits glob; CI builds have no agent-config, so the generator's own entries are
# the only resource includes and must actually take effect)
for pat in ('org/hibernate/.*\\.(dtd|xsd)', 'jakarta/persistence/.*\\.(dtd|xsd)'):
    if not any(p and p == pat for p in res_patterns):
        merged_res.append({'glob': pat})

out_dir = 'target/classes/META-INF/native-image/com.zzh/ni-logger-config'
os.makedirs(out_dir, exist_ok=True)
with open(os.path.join(out_dir, 'reachability-metadata.json'), 'w') as f:
    json.dump({'reflection': merged, 'resources': merged_res}, f, indent=2)

# DTD/XSD schema resources are registered in the classic resource-config.json
# (includes), NOT via the consolidated reachability-metadata.json resources
# section: on GraalVM 25 the latter does not include resources into the image
# (verified: both 'pattern' and 'glob' keys are silently ignored there), while
# classic resource-config.json includes do work. Without this, EMF boot fails
# with XmlInfrastructureException: Unable to locate schema
# [org/hibernate/hibernate-mapping-3.0.dtd] via classpath
with open(os.path.join(out_dir, 'resource-config.json'), 'w') as f:
    json.dump({'resources': {'includes': [
        {'pattern': 'org/hibernate/.*\\.(dtd|xsd)'},
        {'pattern': 'jakarta/persistence/.*\\.(dtd|xsd)'},
    ], 'excludes': [
        {'pattern': 'META-INF/services/org\\.hibernate\\.bytecode\\.spi\\.BytecodeProvider'},
    ]}}, f, indent=2)
print(f'gen-logger-config: agent dirs merged: {agent_dirs or "(none)"}')
print(f'gen-logger-config: registered {len(merged)} reflection entries '
      f'(+{len(merged) - len(agent_meta.get("reflection", []))} from generator, '
      f'{len(openai_any_setter)} openai any-setter classes, '
      f'{len(openai_core_methods)} openai core classes / +{openai_method_count} methods) and '
      f'{len(merged_res)} resource patterns; BytecodeProvider service excluded')
