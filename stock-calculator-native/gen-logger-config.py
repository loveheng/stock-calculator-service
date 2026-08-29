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

Scans every jar on the native classpath (target/cp.txt), registers all found
generated logger classes plus EXTRA_CLASSES (filtered to classes that actually
exist on the classpath, so it survives hibernate version changes). Output is a
single reachability-metadata.json (new consolidated format, same as the tracing
agent emits) written to target/classes/META-INF/native-image/, where native-image
auto-detects it. Existing entries from other config dirs (e.g. agent capture) are
merged when native-image runs, not here.
"""
import json, os, sys, zipfile

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
os.chdir(BASE)  # stock-calculator-native/

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

def extra_on_classpath(c):
    # array types like 'Foo[]' exist only through their component class
    return c[:-2] in all_names if c.endswith('[]') else c in all_names

present = [c for c in EXTRA_CLASSES if extra_on_classpath(c)]
missing = [c for c in EXTRA_CLASSES if not extra_on_classpath(c)]
if missing:
    print(f'gen-logger-config: {len(missing)} extra classes not on classpath, skipped')

classes = sorted(set(present) | set(logger_classes) | (service_providers & all_names))

# merge any previously captured tracing-agent metadata (target/agent-config) so a
# single consolidated file is produced; agent entries take precedence on conflict
agent_meta = {'reflection': [], 'resources': []}
agent_dir = 'target/agent-config'
if os.path.exists(os.path.join(agent_dir, 'reachability-metadata.json')):
    agent_meta = json.load(open(os.path.join(agent_dir, 'reachability-metadata.json')))

def type_of(entry):
    t = entry.get('type')
    return t.get('name') if isinstance(t, dict) else t

def entry_methods(e):
    return e.setdefault('methods', []) if isinstance(e, dict) else []

existing = {}
for e in agent_meta.get('reflection', []):
    existing[type_of(e)] = e
merged = list(agent_meta.get('reflection', []))
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

res_patterns = {e.get('pattern') or e.get('glob') for e in agent_meta.get('resources', [])}
merged_res = list(agent_meta.get('resources', []))
for pat in ('org/hibernate/.*\\.(dtd|xsd)', 'jakarta/persistence/.*\\.(dtd|xsd)'):
    if not any(p and p == pat for p in res_patterns):
        merged_res.append({'pattern': pat})

out_dir = 'target/classes/META-INF/native-image/com.zzh/ni-logger-config'
os.makedirs(out_dir, exist_ok=True)
with open(os.path.join(out_dir, 'reachability-metadata.json'), 'w') as f:
    json.dump({'reflection': merged, 'resources': merged_res}, f, indent=2)

# Exclusion of the BytecodeProvider service file goes into a classic
# resource-config.json: excludes semantics are guaranteed there, while the
# consolidated reachability-metadata.json resources section is an opaque flat
# pattern list (unknown keys risk a parse failure of the whole file).
with open(os.path.join(out_dir, 'resource-config.json'), 'w') as f:
    json.dump({'resources': {'includes': [], 'excludes': [
        {'pattern': 'META-INF/services/org\\.hibernate\\.bytecode\\.spi\\.BytecodeProvider'},
    ]}}, f, indent=2)
print(f'gen-logger-config: registered {len(merged)} reflection entries '
      f'(+{len(merged) - len(agent_meta.get("reflection", []))} from generator) and '
      f'{len(merged_res)} resource patterns; BytecodeProvider service excluded')
