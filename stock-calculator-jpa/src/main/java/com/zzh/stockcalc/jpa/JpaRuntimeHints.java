package com.zzh.stockcalc.jpa;

import java.util.List;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;

/**
 * JPA / Hibernate native-image 反射注册（docs/architecture/jpa-native-extraction.md P1）：
 * 静态可固化层——框架类按名在运行期反射实例化，静态可达性分析发现不了，缺注册则 native
 * 启动即崩（Could not instantiate named strategy class / NoSuchMethodException / 数组类
 * Cannot reflectively instantiate）。
 *
 * <p>注册内容覆盖四类静态框架需求（均带 classpath 存在性守卫，Hibernate 升版改名致类缺失时
 * 仅静默少注册一条，不报错）：
 * <ol>
 *   <li>反射：naming/implicit 策略、Dialect、id 优化器、hikari 数组、JPA 注解内部类、PG JdbcType
 *       全族、BytecodeProvider none、JsonFormatMapper、StrategySelector 等（见 STATIC_CLASSES）；</li>
 *   <li>资源 include：hibernate / jakarta persistence 的 DTD+XSD（与 resource-config.json 双轨，
 *       RuntimeHints 为主轨；BytecodeProvider service 排除只能 JSON 表达，不在此）；</li>
 *   <li>JDK 动态代理：Spring 共享 EntityManager 代理 + Hibernate Session(unwrap) 安全网；</li>
 *   <li>Java 序列化：常见 JPA 值/ID 类型（UUID/Long/String），二级缓存等序列化场景安全网。</li>
 * </ol>
 *
 * <p>经 {@code META-INF/spring/aot.factories} 注册为 RuntimeHintsRegistrar，消费模块只要
 * 依赖本库，process-aot 即自动产出 hints 注入 native 镜像，无需在每个模块挂 @Import。
 * 同名静态元数据另提交于 META-INF/native-image/com.zzh/jpa-config/reachability-metadata.json
 * 作双轨保险；库自带 native-image.properties 提供通用 -H: 开关，依赖即继承。
 *
 * <p>类名带 classpath 存在性过滤（Class.forName 失败即跳过）：Hibernate 跨大版本改名不致崩，
 * 仅静默少注册一条——后续升版只需在本文件与 JSON 同步改一处。
 *
 * <p>动态段（jboss-logging logger、JpaAnnotation 内部类、各应用自身类）依赖消费方 classpath，
 * 无法固化进库，仍在 scripts/agent-tools/gen-native-metadata.py 构建期扫描。
 */
public class JpaRuntimeHints implements RuntimeHintsRegistrar {

    /** 静态可固化框架类（不含模块特定类如 StockDictEntry，那些在 gen-native-metadata.py） */
    private static final List<String> STATIC_CLASSES = List.of(
        "org.hibernate.boot.model.naming.PhysicalNamingStrategyStandardImpl",
        "org.hibernate.boot.model.naming.PhysicalNamingStrategySnakeCaseImpl",
        "org.hibernate.boot.model.naming.ImplicitNamingStrategyJpaCompliantImpl",
        "org.hibernate.boot.model.naming.ImplicitNamingStrategyLegacyJpaImpl",
        "org.hibernate.boot.model.naming.ImplicitNamingStrategyLegacyHbmImpl",
        "org.hibernate.boot.model.naming.ImplicitNamingStrategyComponentPathImpl",
        "org.hibernate.boot.model.relational.ColumnOrderingStrategyStandard",
        "org.hibernate.boot.model.relational.ColumnOrderingStrategyLegacy",
        "org.hibernate.resource.transaction.backend.jdbc.internal.JdbcResourceLocalTransactionCoordinatorBuilderImpl",
        "org.hibernate.resource.transaction.backend.jta.internal.JtaTransactionCoordinatorBuilderImpl",
        "org.hibernate.query.sqm.mutation.internal.cte.CteInsertStrategy",
        "org.hibernate.query.sqm.mutation.internal.temptable.GlobalTemporaryTableInsertStrategy",
        "org.hibernate.query.sqm.mutation.internal.temptable.LocalTemporaryTableInsertStrategy",
        "org.hibernate.query.sqm.mutation.internal.temptable.PersistentTableInsertStrategy",
        "org.hibernate.query.sqm.mutation.internal.cte.CteMutationStrategy",
        "org.hibernate.query.sqm.mutation.internal.temptable.GlobalTemporaryTableMutationStrategy",
        "org.hibernate.query.sqm.mutation.internal.temptable.LocalTemporaryTableMutationStrategy",
        "org.hibernate.query.sqm.mutation.internal.temptable.PersistentTableMutationStrategy",
        "org.hibernate.id.enhanced.StandardNamingStrategy",
        "org.hibernate.id.enhanced.SingleNamingStrategy",
        "org.hibernate.id.enhanced.LegacyNamingStrategy",
        "org.hibernate.cache.internal.DefaultCacheKeysFactory",
        "org.hibernate.cache.internal.SimpleCacheKeysFactory",
        "org.hibernate.type.format.jackson.JacksonJsonFormatMapper",
        "org.hibernate.type.format.jackson.Jackson3JsonFormatMapper",
        "org.hibernate.type.format.jackson.JacksonXmlFormatMapper",
        "org.hibernate.type.format.jaxb.JaxbXmlFormatMapper",
        "org.hibernate.dialect.PostgreSQLDialect",
        "org.hibernate.dialect.type.PostgreSQLArrayJdbcType",
        "org.hibernate.dialect.type.PostgreSQLArrayJdbcTypeConstructor",
        "org.hibernate.dialect.type.PostgreSQLCastingInetJdbcType",
        "org.hibernate.dialect.type.PostgreSQLCastingIntervalSecondJdbcType",
        "org.hibernate.dialect.type.PostgreSQLCastingJsonArrayJdbcType",
        "org.hibernate.dialect.type.PostgreSQLCastingJsonArrayJdbcTypeConstructor",
        "org.hibernate.dialect.type.PostgreSQLCastingJsonJdbcType",
        "org.hibernate.dialect.type.PostgreSQLEnumJdbcType",
        "org.hibernate.dialect.type.PostgreSQLInetJdbcType",
        "org.hibernate.dialect.type.PostgreSQLIntervalSecondJdbcType",
        "org.hibernate.dialect.type.PostgreSQLJsonArrayPGObjectJsonJdbcTypeConstructor",
        "org.hibernate.dialect.type.PostgreSQLJsonArrayPGObjectJsonbJdbcTypeConstructor",
        "org.hibernate.dialect.type.PostgreSQLJsonArrayPGObjectType",
        "org.hibernate.dialect.type.PostgreSQLJsonPGObjectJsonType",
        "org.hibernate.dialect.type.PostgreSQLJsonPGObjectJsonbType",
        "org.hibernate.dialect.type.PostgreSQLOrdinalEnumJdbcType",
        "org.hibernate.dialect.type.PostgreSQLStructCastingJdbcType",
        "org.hibernate.dialect.type.PostgreSQLStructPGObjectJdbcType",
        "org.hibernate.dialect.type.PostgreSQLUUIDJdbcType",
        "org.hibernate.boot.models.annotations.internal.CacheAnnotation",
        "org.hibernate.id.enhanced.NoopOptimizer",
        "org.hibernate.id.enhanced.PooledOptimizer",
        "org.hibernate.id.enhanced.PooledLoOptimizer",
        "org.hibernate.id.enhanced.HiLoOptimizer",
        "org.hibernate.id.enhanced.LegacyHiLoAlgorithmOptimizer",
        "com.zaxxer.hikari.util.ConcurrentBag$IConcurrentBagEntry",
        "com.zaxxer.hikari.util.ConcurrentBag$IConcurrentBagEntry[]",
        "java.util.UUID[]",
        "java.lang.Long[]",
        "java.lang.String[]",
        "java.sql.Statement[]",
        "java.sql.ResultSet[]",
        "java.sql.Connection[]",
        "org.hibernate.bytecode.internal.none.BytecodeProviderImpl",
        "org.hibernate.boot.registry.selector.internal.StrategySelectorImpl"
    );

    /** JDK 动态代理接口（框架级安全网；classpath 缺失则跳过，不报错） */
    private static final List<String> PROXY_INTERFACES = List.of(
        "jakarta.persistence.EntityManager",   // Spring SharedEntityManagerCreator 代理
        "org.hibernate.Session"                // entityManager.unwrap(Session.class)
    );

    /** Java 序列化类型（框架级安全网；同上 classpath 守卫） */
    private static final List<String> SERIALIZABLE_TYPES = List.of(
        "java.util.UUID",   // 常见 @Id 类型
        "java.lang.Long",   // 常见 @Id 类型
        "java.lang.String"
    );

    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
        // 1) 静态框架类反射（含数组类可达）
        for (String fq : STATIC_CLASSES) {
            Class<?> c = resolve(fq, classLoader);
            if (c == null) {
                continue;
            }
            if (fq.endsWith("[]")) {
                // 数组类只需可达（运行期 Array.newInstance 实例化其元素），无需成员
                hints.reflection().registerType(c);
            } else {
                hints.reflection().registerType(c, MemberCategory.values());
            }
        }

        // 2) 资源 include：hibernate / jakarta persistence 的 DTD+XSD
        //    （与 resource-config.json 双轨，RuntimeHints 为主轨；service 排除只能 JSON 表达，不在此）
        hints.resources().registerPattern("org/hibernate/.*\\.(dtd|xsd)");
        hints.resources().registerPattern("jakarta/persistence/.*\\.(dtd|xsd)");

        // 3) JDK 动态代理：EntityManager 代理 + Session(unwrap) 安全网
        for (String fq : PROXY_INTERFACES) {
            Class<?> c = resolve(fq, classLoader);
            if (c != null) {
                hints.proxies().registerJdkProxy(c);
            }
        }

        // 4) Java 序列化：常见 JPA 值/ID 类型安全网（UUID/Long/String 均 Serializable，强转满足边界）
        for (String fq : SERIALIZABLE_TYPES) {
            Class<?> c = resolve(fq, classLoader);
            if (c != null) {
                @SuppressWarnings("unchecked")
                Class<? extends java.io.Serializable> s = (Class<? extends java.io.Serializable>) c;
                hints.serialization().registerType(s);
            }
        }
    }

    private static Class<?> resolve(String fq, ClassLoader cl) {
        try {
            if (fq.endsWith("[]")) {
                String comp = fq.substring(0, fq.length() - 2);
                return Class.forName("[L" + comp + ";", false, cl);
            }
            return Class.forName(fq, false, cl);
        } catch (Throwable t) {
            // classpath 上不存在（如 Hibernate 升版改名）→ 静默跳过，留待升版时同步
            return null;
        }
    }
}
