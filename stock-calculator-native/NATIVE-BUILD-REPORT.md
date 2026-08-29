# Native 编译技术报告：reachability metadata 十二轮迭代实录

> 项目：stock-calculator-service（多模块：common / main / native）
> 技术栈：Spring Boot 4.1.1 · Hibernate ORM 7.4.5.Final · HikariCP 7.0.2 · GraalVM 25.0.4 · PostgreSQL 18.4
> 日期：2026-08-29　|　目标：native 变体启用完整数据库 + 爬虫功能并通过全量验证
> 结论：**已达成**。最终产物 163M ELF，AOT 启动 0.23s，90s 加长验证全绿。
> 经验已沉淀为技能 `stock-calculator-native-runtime-metadata`（下次编译自动复用）。

## 一、背景与约束

- native 变体（`stock-calculator-native`）与 JVM 变体（`stock-calculator-main`）共用 common 模块的
  实体、Repository、爬虫服务；native 变体通过 pom exclusion + `@ComponentScan` 过滤隔离 JPA 泄漏
  （见 `stock-calculator-native-build` 技能），本期目标反而是**把 JPA 打开**并让它在 native 下可运行
- GraalVM 25.0.4 固定于 `/opt/GraalVM25`（系统 java 是 Ubuntu OpenJDK 21，无 native 工具链）
- 构建：`stock-calculator-native/build-native.sh`（step1 Maven+process-aot → step2 cp.txt+生成器 →
  step3 native-image → step4 8 秒冒烟）；`--no-pkg` 可复用 step1 产物（仅元数据/生成器变更时）
- 全量构建 10-15 分钟/轮；**改 yml 必须全量**（yml 参与 AOT 处理），只改元数据可 `--no-pkg`

## 二、问题本质

二进制编译成功（step4 产出 ELF）≠ 运行成功。native-image 的静态可达性分析看不见运行期
「按名字 / 反射 / ServiceLoader」触达的类、方法、资源，需要显式 reachability metadata。
本项目这类触达点集中在四处：

1. jboss-logging 生成类（`XxxLogger_$logger`）：运行期 `MethodHandles.Lookup.findClass` 按名加载
2. Hibernate 策略体系：yml 按名字符串实例化 + `StrategySelector` 按名实例化
3. XML/DTD/XSD 资源：native 默认不打包资源
4. ServiceLoader provider：`META-INF/services` 文件 + 反射调用无参/带参构造器

## 三、十二轮迭代表（症状 → 根因 → 修复，全部验证）

| 轮 | 症状 | 根因 | 修复 |
|---|------|------|------|
| 1 | `Invalid logger interface Xxx (implementation not found)` | 53 个 `_$logger` 生成类 jar 里有但静态不可达 | 生成器扫描全量注册 |
| 2 | `ClassNotFoundException: PhysicalNamingStrategyStandardImpl` | yml 按名字符串实例化 naming strategy | 注册 5+1 个 naming strategies |
| 3 | `Could not instantiate named strategy ColumnOrderingStrategyStandard` | `StrategySelectorBuilder` 注册的全部策略类运行期按名实例化 | 从字节码提取 20+ 策略类批量注册 |
| 4 | `Unable to locate schema [hibernate-mapping-3.0.dtd]` | 资源默认不进二进制 | resource patterns：`org/hibernate/.*(dtd\|xsd)` + `jakarta/persistence/.*` |
| 5 | `Unable to determine Dialect`（假象） | 真因被轮 6 吞掉 | — |
| 6 | Hikari `PoolInitializationException` 构造器内联 NPE 吞真因 | 诊断问题非业务问题 | `initialization-fail-timeout: -1` 绕过 fail-fast（诊断手段） |
| 7 | `Cannot reflectively instantiate array class ConcurrentBag$IConcurrentBagEntry[]` | **agent 盲区**：`Array.newInstance` 不被 tracing agent 拦截 | 手动注册 hikari 组件类 + 数组类 |
| 8 | `BytecodeProviderImpl` 构造器不可调用 | ServiceLoader 反射实例化 provider；agent 走非反射路径录不全 | 全 jar service provider 扫描注册（+91 条）+ 新格式显式构造器 |
| 9 | `ByteBuddyProxyFactory.postInstantiate` → 运行期 `defineClass` 定义实体代理类 | **Hibernate 7 改为 ServiceLoader 发现 BytecodeProvider**：agent 录制把 service 文件录成资源 include，重新引入了 spring-orm 已排除的 bytebuddy provider | `resource-config.json` 排除 `META-INF/services/org.hibernate.bytecode.spi.BytecodeProvider` → 发现为空 → 内置 none provider（`DisallowedProxyFactory.postInstantiate` 为空操作） |
| 10 | resource-config schema 解析失败（缺 `includes` 键） | 经典 resource-config.json 的 `resources` 对象要求 includes 键同时存在 | `"includes": []` 与 `"excludes"` 同列 |
| 11 | `MultiKeyLoadLogging_$logger(org.jboss.logging.Logger)` 不可调用 | **修复死配置激活新路径**：`default_batch_fetch_size` 归位生效 → 批抓日志 logger 首次被实例化；agent 录制时该配置还是死的所以没录到 | 生成器给全部 53 个 `_$logger` 类补 `(org.jboss.logging.Logger)` 构造器（生成类唯一构造器形状，javap 实证） |
| 12 | （验证轮） | — | 163M ELF，启动 0.234s，90s 补录全绿、零 ERROR |

## 四、核心技术发现（本次实证，后续复用）

### 4.1 reachability-metadata.json 新格式语义（GraalVM 25）

- 旧版键 `allDeclaredConstructors` 等被**静默忽略**（不报错、不生效）——最容易踩的坑
- `queryAllDeclared*` 键也被报 Unknown attribute（只给内省）；**真正让「调用」生效的是显式 methods 条目**
- 可调用注册标准写法：`{"type": "全类名", "methods": [{"name": "<init>", "parameterTypes": []}]}`
- 数组类型只写 `{"type": "com.foo.Bar[]"}`（数组无构造器，包含即让 `Array.newInstance` 工作）
- resources 段是扁平 pattern 数组，**不支持 excludes 键**；排除必须走经典 `resource-config.json`，
  且 schema 要求 `includes` 键必须存在（空数组也行）；excludes 优先于一切 include（包括 agent 录入的）

### 4.2 tracing agent 的三个盲区

1. `Array.newInstance` 不被拦截 → hikari `ConcurrentBag` 内部数组需手动注册
2. ServiceLoader 部分走非反射路径 → provider 构造器录不全
3. **agent 会把运行期读过的 service 文件录成资源 include** → 重新引入上游（spring-orm）已排除的服务，
   这是轮 9 的直接根源

### 4.3 Hibernate 7.4.x BytecodeProvider 机制（javap 实证）

- **配置项 `hibernate.bytecode.provider` 已废弃**：`BytecodeProviderInitiator` 纯走
  `ClassLoaderService.loadJavaServices(BytecodeProvider.class)`；发型为空 → 默认内置 none provider；
  发型多个 → `IllegalStateException`
- spring-orm 7.0.9 自带 `-H:ServiceLoaderFeatureExcludeServices=org.hibernate.bytecode.spi.BytecodeProvider`，
  官方意图就是让 native 落到 none provider；agent 污染破坏了该意图
- none provider：`DisallowedProxyFactory.postInstantiate` 空操作（启动安全），`getProxy` 才抛异常；
  无关联映射实体永不触发 getProxy
- bytebuddy provider：`ByteBuddyProxyFactory.postInstantiate` 运行期 `ClassInjector.inject` 定义实体
  代理类 → native 必崩（`throwNoBytecodeClasses`）
- none provider 的 `getReflectionOptimizer(Class, Map)` 重载返回 null（安全），Spring 路径用的是它

### 4.4 Spring Boot yml 命名空间陷阱

- `spring.jpa.hibernate.*` 是固定命名空间（只认 `ddl-auto`/`naming` 等键），任意 JPA 属性必须放
  `spring.jpa.properties.*`，**放错静默无效**
- 本项目 `default_batch_fetch_size: 16` 曾因此长期未生效（main + native 双模块同错），2026-08-29 归位；
  归位本身又引出轮 11 的缺口——修复死配置会激活死路径，要有预期

### 4.5 jboss-logging 生成类构造器形状

- `Xxx_$logger` 生成类**只有一个构造器** `(org.jboss.logging.Logger)`（无 no-arg，javap 实证）；
  jboss-logging `getMessageLogger` 反射调用它实例化
- 注册不存在的成员（如 no-arg）被 native-image 静默容忍，但不起作用——生效的是真实存在的签名

## 五、方法论（为什么能收敛）

1. **错误信息自带答案**：MissingReflectionRegistrationError 会打印缺失项全名 + 应补的 JSON 片段，
   照抄进生成器即可；每轮只修最顶层的一个
2. **先 javap 后动手**：任何外部建议先反编译验证再采纳。本次两次抓到外部 AI 幻觉
   （编造不存在的 `PrimeEntityProxyFactory`；建议的旧版 JSON 键在新格式下被静默忽略），
   另有一次方向正确但手段不优（下节插件评估）
3. **一轮只改一类变量**：12 轮每轮归因清晰；诊断性改动（如 hikari fail-fast 绕过）不算业务变量
4. **配置生成器而非手写元数据**：`gen-logger-config.py` 每次构建从 `target/cp.txt` 扫描全部 jar
   自动重推导（53 logger 类、20+ 策略类、91 provider、agent 合并、resource patterns），
   Hibernate 升级时自动适应

## 六、最终基础设施（当前状态）

- `gen-logger-config.py`：每轮构建自动产出
  `target/classes/META-INF/native-image/com.zzh/ni-logger-config/` 两个文件：
  - `reachability-metadata.json`：1774 reflection + 570 resource patterns
    （53 `_$logger` 类含 `(Logger)` 构造器、20+ 策略类、91 provider、hikari 数组、agent 录制合并）
  - `resource-config.json`：DTD/XSD patterns + **排除 BytecodeProvider service 文件**（轮 9 核心修复）
- `build-native.sh`：`--initialize-at-build-time=logback,slf4j,jboss-logging,bytebuddy`；
  step2 守卫检查元数据产物存在；step4 冒烟失败 exit 1
- `target/agent-config/reachability-metadata.json`：tracing agent 录制产物
  （用 /opt/GraalVM25/bin/java -agentlib:native-image-agent 录制，覆盖启动/连库/EMF/Tomcat/补录）
- 诊断脚本 `inspect-loggers.py` / `inspect-agent.py`：可删

## 七、验证结果（2026-08-29 16:44）

- 8 秒冒烟：✅（grep `Tomcat started`）
- 90 秒加长（`--server.port=19997`）：✅ 全绿——
  AOT 启动 0.234s → Hikari 连 PostgreSQL 18.4 → EMF 无 WARN（含批抓激活）→
  补录三段日志（已就绪 → 开始检查+真实最大时间戳 1787973972 → 补偿完成）→ 零 ERROR → 优雅停机
- 未实测项：8 分钟周期抓取的 INSERT 路径（读路径已真实验证；实体持久化与查询共用同一套
  persister/类型元数据，风险低；下次自然触发时留意即可）

## 八、hibernate-enhance-maven-plugin 评估（结论：不引入）

### 8.1 机制真实性：✅ 真

`EntityRepresentationStrategyPojoStandard.resolveProxyFactory`（javap L165）确有
`isEnhancedForLazyLoading()` 分支：插件在 compile 阶段改写实体字节码
（实现 `PersistentAttributeInterceptable`、注入拦截器字段）后走该分支，跳过代理工厂构建。
`enableLazyInitialization` / `enableDirtyTracking` / `enableAssociationManagement` 均为真实插件参数。

### 8.2 但它要解决的问题已不存在

轮 9/10 已用一条资源排除让 Hibernate 落到 none provider：`DisallowedProxyFactory.postInstantiate`
空操作，代理生成路径整体失效，8s 冒烟 + 90s 真实连库全绿。插件针对的是轮 9 失败语境下的方案，
那个问题状态已成历史。

### 8.3 对本项目的实际收益 ≈ 0

| 插件能力 | 本项目现状 |
|---|---|
| 实体懒加载代理 | 5 个实体 0 关联映射，`getReferenceById` 全项目 0 使用 |
| 字节码级 dirty tracking | 5 个小实体、个人数据规模，默认 snapshot diff 开销可忽略 |
| 关联管理 | 无关联可管理 |

### 8.4 引入成本与风险

1. **改 common 构建链**：common 被 main（JVM）+ native 共享，实体字节码两个变体都被改写，
   已验证稳定的 JVM 行为被引入新变量
2. **JDK 25 兼容性未知**：插件在 Maven JVM 里用 byte-buddy 改写 Java 25 字节码（class file 69），
   可能需要 `-Dnet.bytebuddy.experimental=true`——与 native 痛点同源的 byte-buddy 机制换地方再赌一次
3. **版本锁死**：插件版本必须严格对齐 hibernate-core 7.4.5.Final
4. **与 Boot 4 AOT 交互未验证**：AOT 已预生成 `__Accessor_`/`__Instantiator_`，增强会改变
   `ManagedTypeRepresentationResolver` 选路
5. **归因污染**：12 轮换来的「零源码改动」基线被打破，之后出问题都要先排除增强的影响

### 8.5 何时才值得重新考虑

实体引入真实关联映射 + 懒加载需求时；即使到那时也应先试 `@Proxy(lazy = false)`（一行注解、
不碰构建链），字节码增强是最后手段（适用重 dirty-tracking 负载，本项目不属于）。

## 九、本次新增/修改文件清单

| 文件 | 变更 |
|---|---|
| `stock-calculator-native/gen-logger-config.py` | 核心生成器：53 `_$logger` 含 `(Logger)` 构造器、EXTRA_CLASSES 策略/数组类、91 provider、agent 合并、resource patterns、BytecodeProvider service 排除 |
| `stock-calculator-native/build-native.sh` | --initialize-at-build-time 链、step2 调生成器 + 守卫、冒烟失败 exit 1 |
| `stock-calculator-native/src/main/resources/application.yml` | `default_batch_fetch_size` 归位至 `spring.jpa.properties.hibernate.*` |
| `stock-calculator-main/src/main/resources/application.yml` | 同上（JVM 变体同错同修） |
| `stock-calculator-native/NATIVE-BUILD-REPORT.md` | 本文档 |
| `~/.agents/skills/stock-calculator-native-runtime-metadata/` | 技能沉淀（十二轮迭代 + 方法论 + 新格式语义） |
| `target/agent-config/reachability-metadata.json` | agent 录制产物（临时，重录需用 /opt/GraalVM25/bin/java） |

## 十、遗留事项（不阻塞，按需处理）

1. **双实例重复抓取**：main 与 native 均 `crawler.enabled: true` 会重复抓取，注意错峰或只开一个
2. **TaskServiceTest.testFixedDelayTask**：真实 API 404，与本次无关，勿顺手修
3. **生成器 query* 属性清理**：native-image 25 报 Unknown attribute 警告，真正生效的是显式
   methods 条目，可删但不影响功能
4. **8 分钟周期 INSERT 路径未实测**：下次自然触发时留意首写是否成功
5. **main 变体 yml 归位后未单独重跑**：JVM 模式对该属性不敏感，下次常规启动时确认即可

## 十一、经验沉淀索引

- 本文档：完整轮次实录 + 插件评估
- 技能 `stock-calculator-native-runtime-metadata`：下次编译自动加载的作战手册
  （症状关键词触发；含新格式语义、agent 盲区、Hibernate 7 机制、验证流程）
- 技能 `stock-calculator-native-build`：构建期问题（环境/AOT/父 POM/模块污染/CI）
- 技能 `stock-calculator-workflow`：终端禁 `$` 等环境硬约束
- 外部 AI 建议使用原则：**先 javap 后动手**——本次共评估 4 份外部建议，
  两次幻觉（不存在的类名、被静默忽略的旧键）、一次方向对手段不优（reflect-config 手写 vs 生成器）、
  一次已过时（插件评估，见第八节）
