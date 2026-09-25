---
name: stock-calculator-native-runtime-metadata
description: stock-calculator-service native 二进制「编译成功但运行期崩溃/静默失效」的迭代修复方法（reachability metadata）。适用：build-native.sh 产出 ELF 但冒烟/真实业务失败，报反射/类/资源缺口（ClassNotFoundException、Invalid logger interface 等典型报错），或 @Scheduled 不跑、外链请求丢参等零报错静默失效；含 gen-logger-config.py 生成器、agent 录制、javap 验证与十八轮修复目录。构建期问题归 stock-calculator-native-build。
---

# stock-calculator-service Native 运行期反射崩溃修复（reachability metadata 迭代法）

修复 native 二进制运行期崩溃时严格执行本技能：先 javap 验证根因 → 改生成器 → 构建 → 冒烟 → 加长验证。
构建期问题（环境、AOT 产物、父 POM install、模块依赖污染、CI）见 `stock-calculator-native-build`。

## 一、适用场景与判断

- 症状：`build-native.sh` 走到步骤 4（ELF 产出）但 8 秒冒烟失败；或冒烟过了但 90s 加长验证中首次真实业务（补录查询/写入）崩溃
- 典型报错关键词：`Invalid logger interface` / `ClassNotFoundException` / `Could not instantiate named strategy` / `Unable to locate schema` / `NoSuchMethodException` / `Cannot reflectively instantiate array class` / `ByteBuddyProxyFactory` / `throwNoBytecodeClasses` / `Cannot reflectively invoke method`（openai SDK 双 Jackson 面，见十一）/ `OpenAIInvalidDataException: Error reading response`（包装异常，必看 Caused by）
- 本质：类、方法或资源只在运行期被「按名字 / 反射 / ServiceLoader」触达，静态可达性分析看不见，native-image 需要显式元数据
- 运行期功能**静默失效**（零报错：编译过、启动过，但功能不工作）也属本技能范围，如 @Scheduled 不跑、外链请求丢参，排查套路见第八节

## 二、核心心法（每轮迭代必守）

1. 错误信息自带答案：报错会打印缺失项全名（类/方法/资源），按格式补进生成器即可，先修最顶层的那个
2. 先 javap 后动手：外部建议（含用户转贴的 AI 方案）必须反编译验证再采纳。
   `javap -c -p -cp <jar路径> <类全名>`（用 /opt/GraalVM25/bin/javap）；
   jar 内资源与 service 文件用 `unzip -l` / `unzip -p` 查看。
   本项目已两次抓到外部 AI 幻觉：编造不存在的 Hibernate 类名；建议的 JSON 键被新格式静默忽略
3. 一轮只改一类变量：每轮全量构建 10-15 分钟，批量修复失去归因能力；诊断性改动（如 hikari `initialization-fail-timeout: -1` 绕过 fail-fast 拿真因）不算业务变量
4. 改 yml 必须全量构建（yml 参与 AOT 处理，`--no-pkg` 无效）；只改生成器/元数据可 `--no-pkg`（复用 step1 产物）
5. 报错被吞时找真因：Hikari `PoolInitializationException` 构造器内联 NPE 会掩盖根因，先拿真因再修

## 三、基础设施（已就位，勿重复造）

- `stock-calculator-main/build-native.sh`（2026-08-31 起 native 模块已删除，main 即唯一构建入口，-J-Xmx12g）：
  step1 Maven（父 POM+common install、main compile+process-aot）→
  step2 生成 cp.txt + 调 gen-logger-config.py（守卫检查产物存在）→
  step3 native-image（--initialize-at-build-time=ch.qos.logback.classic,ch.qos.logback.core,org.slf4j,org.jboss.logging,net.bytebuddy）→
  step4 8 秒冒烟（grep Tomcat started，失败 exit 1）
- `stock-calculator-main/gen-logger-config.py`：每次构建从 target/cp.txt 扫描全部 jar，自动推导：
  - jboss-logging 生成的 XxxLogger_$logger 类（运行期 MethodHandles.Lookup.findClass 按名加载，静态不可达）
  - EXTRA_CLASSES：yml/框架按名实例化的策略类 + agent 盲区数组类（hikari ConcurrentBag、
    实体 id 数组 UUID[]/Long[]/String[]，见七轮15）；写入前过滤 classpath 真实存在，
    JDK 前缀（java./javax./sun./com.sun./jdk.）直接放行（JDK 类不在 jar 扫描结果里），Hibernate 升级安全
  - 全部 META-INF/services provider 的无参构造器（约 +91 条）
  - openai-java 反射面（2026-09，详见十一）：字节码含 putAdditionalProperty 常量的类显式注册 any-setter（4,719 类，轮 17）+ com.openai.core.** 全部类注册全部声明方法（纯 Python class 文件解析器，200 类 +1,074 条显式 methods，轮 18）
  - 与 agent 录制合并：agent-config（基线，随仓库提交）+ 可选 agent-config-llm 多目录并集，同类型 methods/fields 按全签名取并集，生成器条目补缺
  - 产物写 target/classes/META-INF/native-image/com.zzh/ni-logger-config/（reachability-metadata.json + resource-config.json），native-image 自动检测——不存在「src 资源未同步」问题
- agent 录制方法（需重录时）：一键脚本 `POSTGRES_PASS=... bash stock-calculator-main/record-agent.sh 75`
  （classpath 生成+test jar 剥离 → /opt/GraalVM25/bin/java
  `-agentlib:native-image-agent=config-output-dir=agent-config -cp <CP> 主类 --server.port=19998
  --spring.main.lazy-initialization=false` → SIGTERM 优雅停机 flush）。
  **关键：lazy-initialization=false 让全部单例（Spring AI/auth/爬虫）装配并被录制**，
  且补录真实落库会把批抓路径的 id 数组也录进去；系统 java 无 agent 库，必须用 GraalVM 的 java。
  2026-09 起脚本支持：`AGENT_OUT=<dir>`（独立输出目录，不 rm 基线 agent-config）、
  `JAVA_OPTS`（透传 JVM 级参数，如代理 -D——必须加在 java 命令行 -cp 之前，不能当 Spring Boot 应用参数）、
  录制秒数后的 app 参数透传（如 --llm.gemini.base-url 指向本地 mock）。
  录制环境三坑（2026-09-02 实测）：① spring.ai.model.audio.speech 缺省即激活且强制要凭证，lazy-off 启动即炸
  → 加 `--spring.ai.model.audio.speech=none --spring.ai.openai.api-key=mock-key`；
  ② 真实 AI 端点（groq/azure/google）必须走代理（-D 系统属性），否则渠道健康检查全挂、请求全 503；
  ③ 带代理后启动健康检查耗时暴涨（实测 194s），录制窗口与探活必须大于实测启动时长。
  结论：缺口集中在单一第三方包时，包级全量注册（十一）严格优于 agent 一次录制——agent 只录走到的路径；录制仅作保险

## 四、reachability-metadata.json 新格式语义（GraalVM 25 实测，最重要）

1. 旧版键 allDeclaredConstructors/allPublicMethods 等被**静默忽略**（不报错、不生效）
2. queryAllDeclared* 键也被报 Unknown attribute（只给内省）——真正让「调用」生效的是显式 methods 条目
3. 可调用注册的标准写法：
   `{"type": "全类名", "methods": [{"name": "<init>", "parameterTypes": []}]}`
   （ServiceLoader.newInstance 与 StrategySelector 只调无参构造器）
4. 数组类型只写 `{"type": "com.foo.Bar[]"}`：数组无构造器，包含即让 Array.newInstance 工作
5. resources 段是扁平 pattern 数组（pattern/glob），**不接受 excludes 键**（未知键可能整文件解析失败）
6. 排除资源必须用经典 resource-config.json（仍完全支持），且 schema 要求 includes 键必须存在（空数组也行）：
   `{"resources": {"includes": [], "excludes": [{"pattern": "META-INF/services/org\\.hibernate\\.bytecode\\.spi\\.BytecodeProvider"}]}}`
   excludes 优先于一切 include（包括 agent 录入的）

## 五、tracing agent 盲区（勿信 agent 万能）

- Array.newInstance 不被拦截：hikari ConcurrentBag 的 IConcurrentBagEntry[] 与 PoolEntry[] 需手动注册（EXTRA_CLASSES）
- ServiceLoader 部分走非反射路径，录不全 provider 构造器
- agent 把运行期读过的 service 文件录成资源 include——会重新引入上游已排除的服务（见六）
- 录制后新增的实体 id 类型 → 对应数组类缺失（轮 15 UUID[] 教训）；lazy-off 录制让批加载器
  真实执行可大幅缓解，生成器仍保留防御条目

## 六、Hibernate 7.4.x 关键机制（javap 实证，2026-08）

1. BytecodeProvider 发现纯走 ServiceLoader：`BytecodeProviderInitiator.initiateService` 调
   `ClassLoaderService.loadJavaServices(BytecodeProvider.class)`，配置项 `hibernate.bytecode.provider` 已废弃无效。
   发型为空 → 默认内置 none provider（org.hibernate.bytecode.internal.none.BytecodeProviderImpl）；
   发型 1 个 → 用它；发型多个 → IllegalStateException
2. spring-orm 7.0.9 自带 `-H:ServiceLoaderFeatureExcludeServices=org.hibernate.bytecode.spi.BytecodeProvider`
   （native-image.properties），意图就是让 native 落到 none provider；
   agent 录制的资源 include 会破坏该意图 → 运行期实例化 bytebuddy provider → 崩溃。
   修复 = resource-config.json excludes 抵消 agent 污染（已入生成器）
3. none provider 行为：`DisallowedProxyFactory.postInstantiate` 是空操作（启动安全），
   `getProxy` 才抛 HibernateException；无关联映射、无 getReferenceById 的实体永不触发 getProxy
4. bytebuddy provider 行为：`ByteBuddyProxyFactory.postInstantiate` → ByteBuddyState.loadProxy →
   ClassInjector.inject 运行期定义实体代理类 → native 必崩（throwNoBytecodeClasses）
5. none provider 的 `getReflectionOptimizer(Class, Map)` 重载返回 null（安全），
   另一个 (Class,String[],String[],Class[]) 重载才抛异常——Spring 路径用的是安全重载
6. Boot yml 陷阱：`spring.jpa.hibernate.*` 是固定命名空间（只认 ddl-auto/naming 等键），
   任意 JPA 属性必须放 `spring.jpa.properties.*`，放错**静默无效**
   （default_batch_fetch_size: 16 曾因此两模块长期未生效，2026-08-29 已归位）
7. default_batch_fetch_size 激活后，EMF 初始化为每个实体建 EntityBatchLoaderArrayParam，
   经 MultiKeyLoadHelper/ArrayJavaType 反射 Array.newInstance(id类型[]) —— 缺注册报
   Cannot reflectively instantiate the array class 'java.util.UUID[]'；
   数组注册只写 {"type": "X[]"}（见四.4），组件类用 JDK 前缀放行（见三）

## 七、十八轮修复目录（症状 → 修复，全部验证通过）

| 轮 | 崩溃点 | 修复 |
|---|--------|------|
| 1 | Invalid logger interface Xxx (implementation not found) | 生成器扫描注册全部 _$logger 类 |
| 2 | ClassNotFoundException: PhysicalNamingStrategyStandardImpl | 注册 yml 按名实例化的 naming strategy |
| 3 | Could not instantiate named strategy ColumnOrderingStrategyStandard | 注册 StrategySelectorBuilder 全部策略类 |
| 4 | Unable to locate schema [hibernate-mapping-3.0.dtd] | resource patterns: org/hibernate/.*(dtd\|xsd)、jakarta/persistence/.*(dtd\|xsd) |
| 5 | Unable to determine Dialect（假象） | 真因被轮 6 吞掉，见下 |
| 6 | Hikari PoolInitializationException 内联 NPE 掩盖真因 | initialization-fail-timeout: -1 诊断手段 |
| 7 | Cannot reflectively instantiate array class ConcurrentBag 入口数组 | agent 盲区，手动注册组件类+数组类 |
| 8 | BytecodeProviderImpl 构造器不可调用（ServiceLoader 反射实例化） | provider 全量注册 + 新格式显式无参 ctor |
| 9 | ByteBuddyProxyFactory.postInstantiate 运行期 defineClass | 排除 BytecodeProvider service 资源 → none provider |
| 10 | resource-config schema 缺 includes 键解析失败 | includes: [] 与 excludes 同列；构建+冒烟+90s 全过 |
| 11 | MultiKeyLoadLogging_$logger(org.jboss.logging.Logger) 不可调用 | 根因：修复 default_batch_fetch_size 归位后批抓日志路径首次激活，agent 录制时该配置还是死的所以没录到；生成器给全部 53 个 _$logger 类补 (Logger) 构造器（生成类唯一构造器形状，javap 实证） |
| 12 | （验证轮） | 163M ELF，启动 0.234s，90s 补录全绿、零 ERROR |
| 13 | 部署后 @Scheduled 定时任务完全不跑（无任何报错） | native 启动类缺 @EnableScheduling（模块拆分丢失）；另注意 @ConditionalOnProperty(crawler.enabled, matchIfMissing=false) 不满足时 bean 也不创建 |
| 14 | 任务跑了但外链 404 HTML/418、补录空转 | .uri(url, vars) 模板语义：url 无 {占位符} 时 vars 静默丢弃 → buildGetUri(url, params) + 单参数 .uri(URI) |
| 15 | Cannot reflectively instantiate the array class 'java.util.UUID[]'（EMF 初始化即崩） | 轮 11 归位的批抓配置激活了批加载器路径，而 agent 录制早于 UUID 主键 auth 实体（且 agent 不录 Array.newInstance）→ 生成器 EXTRA_CLASSES 注册实体 id 数组 + JDK 前缀放行过滤 |
| 16 | main 模块（Spring AI 2.0.1，173 jar）直接 native 化：7g 堆 OOM；Boot 4 新式 test jar 泄入 | lazy-off 重录 agent 全量覆盖 + -J-Xmx12g + 剥离模式补 *-test-*\|*resttestclient*；2026-08-31 新基线 303MB ELF / 0.317s / 90s 全绿（native 模块就此删除） |
| 17 | openai SDK 响应反序列化：private any-setter putAdditionalProperty(String,JsonValue) 不可调用（仅响应含 SDK 未建模字段时触发；表层只见 Error reading response） | 生成器扫描 openai jar 全部 class 字节码含该常量的类，显式注册该签名（4,719 类，对仅引用常量的类静默容忍）；AbstractOpenAiCompatibleLlmService 两个 catch 加完整堆栈 WARN（根因可见性） |
| 18 | Jackson 3 序列化：OpenAiChatModel.from() 对 _additionalProperties() 做 tools.jackson convertValue，MethodHandle 反射调 JsonField.isMissing() 未注册；lambda 只 catch Exception 而 MissingReflectionRegistrationError 是 Error 穿透 | 生成器纯 Python class 文件解析器，com.openai.core.** 全部 200 类注册全部声明方法（+1,074 条显式 methods）；--no-pkg 重建 317MB ELF + mock E2E 全绿（方法见十一/十二） |

## 八、Spring 静默失效陷阱（编译过、启动过，功能不工作，零报错）

1. **@EnableScheduling 丢失 → @Scheduled 全部静默不跑**：模块拆分/新增启动类时注解易丢（main 有、native 没有，本项目轮 13 即中招）；另注意 @ConditionalOnProperty(crawler.enabled, matchIfMissing=false) 不满足时 bean 压根不创建，同样无报错。排查顺序：先查启动类注解齐全（@EnableScheduling/@EnableAsync/@ConfigurationPropertiesScan），再查条件属性是否满足
2. **.uri(url, vars) 模板展开语义静默丢参**：DefaultWebClient 的 uri(String, Object...) 只替换 url 中的 {占位符}；url 无占位符时 vars 被静默忽略（不抛错、不告警），实际发出裸路径。带查询参数的正确写法：
   `new DefaultUriBuilderFactory().uriString(url).queryParam(k, v).build()` → WebClient 传单参数 `.uri(URI)`（按原样使用）
   注意 UriBuilder 接口没有 uriString()，必须从 DefaultUriBuilderFactory 实例上调用
3. **丢参诊断证据链**：HttpUtil.getUrlInfo 会把应发送的查询串打 INFO 日志 → 用 curl 复刻同一 URL+参数串（服务端 200 正常）→ 对比 native 日志里实际发出的路径 → 确认客户端丢参
4. **yml 键放错命名空间**：任意 JPA 属性必须放 spring.jpa.properties.*（见六.6），放错静默无效
5. **增量编译假绿**：改 Java 后只增量 compile，旧字节码混编 → AOT 阶段爆 NoClassDefFoundError（裸类名假象）；Java 改动必须 clean 全量构建（详见 native-build 技能）

## 九、标准验证流程

1. 8 秒冒烟（脚本内置，grep Tomcat started）
2. 90 秒加长：`timeout 90 ./target/stock-calculator-service --server.port=19997 > /tmp/ni-run90.log 2>&1`
   合格标准：AOT 启动亚秒级、Hikari 连库、EMF 无 WARN、补录三段日志（已就绪 → 开始检查+真实最大时间戳 → 补偿完成）、零 ERROR、优雅停机
3. 首次真实查询/写入可能暴露新缺口：报错自带缺失项全名 → 补生成器 → 重建（只改元数据可 --no-pkg）
4. 二进制 HTTP 冒烟：`stock-calculator-main/smoke-curl.sh`（无 token POST /api/admin/sync/history/stop
   应得 {"code":403,...} ApiResponse 信封）
5. 2026-09-02 基线（main 模块，Spring AI 全功能 + openai 反射面全量注册）：317MB ELF，
   8s 冒烟过；mock E2E 全链路绿（drafts_ok=True / reflection_error=False / chain_done=True / errors=0，
   mock 响应含未建模字段，走 from() 转换即服务器崩溃同路径）。原预测的 spring-ai-openai DTO 反射缺口
   已由轮 17/18 解决（见十一）。注意：build-native.sh 内置 8s 冒烟在无 POSTGRES_PASS 的 shell 里必报
   Unable to determine Dialect 假象（连不上库，非代码问题）；E2E 脚本自行从 podman 容器读 POSTGRES_PASS

## 十、与其他技能关系

- 终端禁 $、heredoc/单次写入长度限制、分段续写模式：`stock-calculator-workflow`
- 构建期问题（GraalVM 环境、AOT 产物、父 POM install、模块依赖污染、CI）：`stock-calculator-native-build`
- 实体/Service/TaskService 编码规范：`stock-calculator-backend-dev`
- 环境与工具链事实（GraalVM 路径 / 系统 java 无 native-image 工具链）：`stock-calculator-workflow`「环境与工具链」

## 十一、第三方 SDK 反射缺口批量修法（openai-java 4.49 + Spring AI 2.x 双 Jackson 实战，2026-09-02）

1. **症状链**：native 编译过、启动过，真实 LLM 请求必崩（本地 mock 同样崩）。两个关键机制：
   - `MissingReflectionRegistrationError` 是 **Error 不是 Exception**——所有 `catch (Exception)` 防御代码都拦不住
     （如 OpenAiChatModel.lambda$from$20、SDK JsonHandler），直接炸穿到 GlobalExceptionHandler
   - SDK JsonHandler 把解析期 Exception 包成 `OpenAIInvalidDataException("Error reading response")`
     ——见到这个 message 必须追 `Caused by`（轮 17 的堆栈日志增强为此而设）
2. **双 Jackson 反射面**（同一响应的两段路径，修一段不代表另一段安全）：
   - 反序列化：SDK 自带 Jackson 2（com.fasterxml）+ PropertyBasedCreator 构造器创建；jar 自带 classic
     元数据（allDeclared* 在 classic 格式仍有效）覆盖构造器/字段/getter，缺口只有 private any-setter（轮 17）
   - 序列化：Spring AI `OpenAiChatModel.from()` 把 `ChatCompletion._additionalProperties()`
     （Map<String,JsonValue>）经 **Jackson 3（tools.jackson）convertValue**，BeanPropertyWriter 经
     MethodHandle 链反射调 `JsonField.isMissing()`；`MethodHandle.asMethod → Method.acquireMethodAccessor`
     同样要求显式 methods 注册（query* 键只给内省，见四）
   - Gemini OpenAI 兼容端点响应必带未建模字段 → from() 转换必跑必崩
3. **修法 = 包级全量注册，拒绝逐方法打地鼠**：报错自带缺失项全名会诱惑一个个补；同包反射面是整个类图谱，
   补一个冒下一个、每次构建 10-15 分钟。生成器内置**纯 Python class 文件解析器**（常量池遍历 +
   fields/methods 表提取 name+descriptor，descriptor 转点分参数类型，注意 long/double 双槽；
   任何意外 raise 即跳过该类），对 openai jar 内 `com.openai.core.**` 全部类注册全部声明方法。
   CI 安全（不依赖 javap）、确定性（覆盖任意 provider 响应形状）、对不存在成员静默容忍
4. **范围裁剪**：只全量 `com.openai.core.**`（JsonField/JsonValue 家族，200 类 +1,074 方法，
   元数据约 +2MB 无感）；`com.openai.models.**` **不做**——模型类反序列化走构造器（jar classic 元数据覆盖）
   + any-setter（轮 17），getter 全是直接调用不经反射，全量只是白白撑大元数据。后续报错若指向 models 类再扩
5. **验证闭环**：javap 确认 `from()` 字节码 convertValue 源类型与 JsonField 方法形状 → 改生成器 →
   `--no-pkg` 重建 → python 读回 reachability-metadata.json 抽查（JsonField 21 个方法含 isMissing）→
   mock E2E 全链路（见十二）

## 十二、本地 mock OpenAI 服务器 HTTP 陷阱（E2E 假失败排查，2026-09-02）

1. **Content-Length 必须等于实际写出的 payload 长度**：mock 曾声明内层 content 字符串长度、
   却写出整个 completion JSON（610B）→ 客户端按声明长度读完即认为响应结束 → JSON 固定小列号截断
   （`JsonEOFException: Unexpected end-of-input in field name` ~column 130）→ SDK 包成
   "Error reading response"；剩余字节滞留 keep-alive 连接被当下一个请求解析 → mock 侧
   ConnectionResetError。修复 = 先拼完整 payload，`send_header('Content-Length', str(len(payload)))` 后写出
2. **截断归属判别两步分流**（不要先怀疑 native）：JVM 录制与 native E2E 同样截断 → 与 native 无关；
   curl 直连 mock 复现截断 → mock 的锅，与应用/okhttp 无关。修好 mock 前，反射修复的 E2E 结论不可信
   （截断让链路死在反序列化期，根本走不到 from() 转换路径——验证是假闭环）
3. **既有约定勿回退**：HTTP/1.1（protocol_version）+ ThreadingHTTPServer（okhttp 对 HTTP/1.0 提前断连敏感）；
   响应故意带 reasoning_content + mock_unknown_field（触发 any-setter 与 from() 转换）；
   测试图 /tmp/ni-test-image.jpg 会被系统清理，PIL 重建即可
4. 本地脚本（mock_openai_server.py / run-llm-mock-test.py / run-real-llm-test.py / smoke-native.py /
   run-llm-agent-record.py）只留本地，**禁止提交**（硬编码本机容器名/代理 IP/key 路径）
