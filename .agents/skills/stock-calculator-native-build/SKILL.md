---
name: stock-calculator-native-build
description: stock-calculator-service 的 Native 构建与多模块故障排查方法。修复/验证 native-image 编译、AOT 处理、GraalVM 环境、CI workflow、模块依赖污染（JPA 泄漏到 native 变体）等问题；包含「环境探测 → contextLoads 复现 → 构建脚本审查 → AOT 产物验证 → 二进制冒烟」的排查套路与已验证的修复结论。涉及 native 编译、GraalVM、AOT、CI 构建、多模块启动失败的任务时使用。
---

# stock-calculator-service Native 构建排查与修复方法

修复/验证本项目 Native 构建、CI、多模块问题时，严格按本技能的套路执行。
核心工作模式：**先基于分析一次性改完（脚本/POM/yml/CI），再逐步执行验证**；
每次验证只看最小编译单元，逐步放大到端到端。

> 2026-08-31 基线：`stock-calculator-native` 模块已删除，`stock-calculator-main`（Spring AI 变体）
> 直接出 native —— 303MB ELF、启动 0.317s、90s 全绿（0 ERROR/0 WARN）、curl 403 门禁通过。
> 2026-09-11 补课：`stock-calculator-data`（数据服务拆分，零 DB）native 化完成 —— 201MB ELF、
> 启动 0.3s、R1 PDFBox 冒烟 PASS（契约反射/awt JNI/字体资源三缺口，见 §三表与 §四 agent 采集）
> 2026-09-17 修订：§一 模块清单按 `stock-calculator-workflow`「模块结构」收敛（common 已于
> 2026-09-01 并入 main），install / 排查命令同步改为 contract

## 一、项目关键事实（2026-08 验证）

- Maven 模块：contract / main / data 三模块；结构与包名唯一事实源 `stock-calculator-workflow`
  「模块结构」（`stock-calculator-common` 已于 2026-09-01 并入 main，本 skill 不自带模块清单）。
  native 相关补充：contract 纯 POJO，spring-core 仅 optional 供 ContractRuntimeHints
- 两个 native 可构建模块：main（`stock-calculator-main/build-native.sh`，303MB，需 PostgreSQL）
  与 data（`stock-calculator-data/build-native.sh`，201MB，需本地 RabbitMQ/LavinMQ；构建期
  SPRING_APPLICATION_JSON 钉死三角色全开 all-in-one 变体 + dummy 凭据——**AOT 固化条件装配，
  条件评估冻结在构建期，运行期 env 只能改值不能再改条件**）
- main 含全量 JPA/爬虫/auth（原 common 已并入），native 二进制启动需连 PostgreSQL，无任何 exclusion
- 环境与工具链事实（GraalVM 路径与版本 / sdkman 已卸载 / 系统 java 无 native 工具链 / 构建用 ./mvnw）：唯一事实源 `stock-calculator-workflow`「环境与工具链」，本 skill 不复述
- 多模块下**单独构建 native 模块前，必须先把父 POM 与依赖模块 install 进 ~/.m2**
  （忘装 contract 会报 dependency resolution 错；或构建命令一律带 `-am`）：
  ```sh
  ./mvnw install -N                      # 父 POM
  ./mvnw install -pl stock-calculator-contract
  ```
- 启动类问题用 contextLoads 快速复现（不需要真跑应用、不需要数据库）：
  ```sh
  ./mvnw -q -pl stock-calculator-main -am test
  ```

## 二、排查套路（按顺序执行，不要跳步）

1. **环境探测先行**（动手前先摸清现状，不要假设）：
   `which java native-image mvn`、`java -version`、`native-image --version`、
   `ls /opt/GraalVM25 ~/.jdks`、`free -m; nproc`、
   `ls ~/.m2/repository/com/zzh/stock-calculator-contract`
2. **启动类问题用 contextLoads 复现**：`./mvnw -q -pl <模块> -am test`，
   看第一个 BeanCreationException 的 Caused by 链
3. **构建脚本审查清单**（build-native.sh / package-native.sh / CI workflow）：
   - 是否还有 `source ~/.sdkman/...` 或按 `~/.sdkman/candidates/...` 找 JDK
   - 用的是裸 `mvn` 还是 `./mvnw`；路径是否按旧单模块写的（根级 `src/`、根级 `target/`、根级 `Dockerfile.native`）
   - 引用的 `-Pxxx` profile 是否真的存在于某个 POM（grep `<profile>`）
   - classpath 是否包含 AOT 产物；test jar 是否剥离
4. **AOT 产物验证**：`ls target/spring-aot/main/` 必须同时存在 `classes/` 与 `resources/`，
   且 classes 里有 `*__ApplicationContextInitializer.class`。缺失 = process-aot 没执行
5. **端到端冒烟**：启动二进制（`--server.port=19999` + timeout 8）→
   grep `Tomcat started|using Java 25` → curl 接口拿 HTTP 200。
   注意旧脚本的 `timeout ... || true` 会掩盖启动失败，冒烟失败必须 exit 1
 6. **native 运行期缺口**（NoClassDefFoundError/反射/资源缺失，JVM 同链路正常）→ §四 agent 采集

## 三、已踩坑与解法（复用结论，勿重复踩）

| 症状 | 根因 | 解法 |
|------|------|------|
| `source ~/.sdkman/...` 报错 / native-image 或 mvn 找不到 | sdkman 已卸载 | GraalVM 探测顺序 `JAVA_HOME` → `/opt/GraalVM25` → `PATH`；mvn 一律用 `../mvnw` |
| `Could not find artifact com.zzh:stock-calculator-service:pom` | 单独构建子模块但父 POM 未 install | 先 `install -N` 装父 POM，再 `install -pl stock-calculator-contract` |
| 启动报 `AotInitializerNotFoundException: ...__ApplicationContextInitializer could not be found` | AOT 类没进 native-image classpath | classpath 必须含 `target/spring-aot/main/classes`（只有 resources 不够）|
| AOT 产物目录不存在 | `compile process-classes` 不会触发 profile 绑定的 process-aot | 显式调用 `compile spring-boot:process-aot`，不依赖 phase 绑定 |
| native 启动报 `Failed to determine a suitable driver class` | 数据源配置缺失（postgres profile 未激活或 POSTGRES_PASS 未注入） | 确认 profile 激活且口令经环境变量注入；main 全量含 JPA，不需要也不应有 exclusion |
| 缓存 spec / lazy-initialization 不生效 | yml 缩进嵌套错（`cache:`/`main:` 误挂在 `gemini:` 下） | 对照 `spring.*` 标准属性路径逐行核对缩进 |
| CI/脚本在多模块化后失效 | 按旧单模块结构写的引用（根级 src/、Dockerfile.native、target/） | 构建逻辑收敛到 build-native.sh，CI 只调脚本；docker context 指向 main 模块目录（stock-calculator-main/Dockerfile.native） |
| native-image 报 `Terminating due to java.lang.OutOfMemoryError`（日志尾部可能是 netty/bouncycastle 噪音栈） | main 变体 173 jar（Spring AI 全家桶）分析规模大，7g 堆不够 | `-J-Xmx12g`；致命错误看完整日志最后 fatal 行，别被中段噪音栈带偏（netty BouncyCastleAlpnSslUtils 构建期 CNFE 会被自动推迟到运行期，无害） |
| 启动报 spring.factories 相关 `ClassNotFoundException` | Boot 4 新式 test jar（spring-boot-data-jpa-test / spring-boot-resttestclient 等）不匹配 `*spring-boot-starter-test*` 旧模式泄入 classpath | 剥离 case 补 `*-test-*\|*resttestclient*`（2026-08-31 已入 build-native.sh） |
| build-native.sh 内置 8s 冒烟报 Unable to determine Dialect，但 ELF 已正常产出 | 冒烟 shell 没有 POSTGRES_PASS，连不上库的已知假象（非代码问题）| 带 POSTGRES_PASS 重跑冒烟，或直接跑 mock E2E（run-llm-mock-test.py 自行从 podman 容器读口令）验证产物（2026-09-02 实战）|
| native-image 编译失败但看不到日志 | 日志重定向到 /tmp 后被 set -e 吞掉 | 失败分支 `tail` 日志末尾并 `exit 1`（CI 可诊断） |
| native 消费 MQ 信封报 `InvalidDefinitionException ... no delegate- or property-based Creator` | 监听器内手工 `readValue/convertValue` 还原 DTO，Spring AOT 无法从代码签名推断反射 | 契约模块建 `ContractRuntimeHints implements RuntimeHintsRegistrar`（DTO 列表单点登记 + `MemberCategory.values()` 全量 + getNestMembers 递归兜底内部类；新增 DTO 必须同步登记）；`@ImportRuntimeHints` 挂**无条件**配置类——挂条件类下条件 false 时 AOT 不处理，注册丢失 |
| 编译报 `找不到符号: 类 ImportRuntimeHints`（org.springframework.aot.hint.annotation 包不存在） | Framework 7 该注解位于 spring-context，不在 spring-core | `import org.springframework.context.annotation.ImportRuntimeHints;` |
| native 跑 PDFBox 报 `NoClassDefFoundError: java/awt/GraphicsEnvironment` | `PDDocument.<clinit>` 直拉 Raster/ColorModel → `System.loadLibrary("awt")` → libawt 的 C 代码 JNI FindClass，静态分析不可见；伴随 Helvetica.afm 字体资源缺失（`org/apache/pdfbox/resources/`） | native-image-agent 采集（§四），产物落 `src/main/resources/META-INF/native-image/`；PDFBox 不自带 GraalVM 配置 |
| 冒烟 curl HTTP 端点返回 000（连接被拒），但应用日志正常 | yml `web-application-type: none` + pom 无 web starter——控制器从未监听；单测直调 Controller 方法掩盖缺口 | 补 `spring-boot-starter-web` + 删 yml 该行，重建后冒烟断言（如 ingest 503/400） |
| 沙箱终端连 localhost broker 全部连接拒绝，broker 实际存活（podman 容器 lavinmq） | 终端沙箱与主机网络隔离 | 需连本地 broker 的命令一律非沙箱运行；URL 用 `127.0.0.1` 防 IPv6 解析 |
| MCP client 启用后（2026-09-24 yml 修复生效）main native 冒烟挂：`McpSyncClient.initialize` 阻塞中被 kill（InterruptedException），Tomcat 8s 内起不来 | mcpSyncClients bean 创建即连 SSE 并 initialize；CI 隔离 runner 无 orchestration(:18083)，对端不在阻塞到 20s 超时炸启动 | build-native.sh 冒烟步探 `127.0.0.1:18083`，不在则 `export SPRING_AI_MCP_CLIENT_INITIALIZED=false`（initialized 是 bean 工厂方法运行期读取的普通值，非 AOT 冻结条件；enabled 才是条件，运行期改无效）；对端在时保持急切初始化真验 dispatch |
| data native 冒烟炸 `LLM tier [openai-mini] 配置不完整` | smoke-native.sh 的 SPRING_APPLICATION_JSON 还在注旧死键 `datasvc.llm`/`datasvc.worker.embedding`，而 yml 已迁 `ai.tiers.openai-mini`/`ai.embeddings.embed`（供应商可切换改造） | 冒烟 dummy 与构建期钉死段同步迁移命名空间；LLM/embedding 键迁移时必须同步改 build-native.sh 与 smoke-native.sh 两处 |

## 四、运行期动态缺口采集：native-image-agent（2026-09-11 R1 实证）

适用：native 运行报 NoClassDefFoundError / 反射缺失 / 资源缺失，而同一链路 JVM 正常
（JNI FindClass、Class.forName、ClassLoader.getResource 对静态分析均不可见）。

1. **JVM+agent 跑通全链**（本项目模板：`stock-calculator-data/native-r1-smoke.py`，
   环境变量 `R1_JVM_AGENT=1`）：
   - 必须用 **GraalVM 自带 java**（系统 OpenJDK 不带 agent 库）：
     `/opt/GraalVM25/bin/java -agentlib:native-image-agent=config-output-dir=target/agent-configs -cp target/classes:<classpath> <MainClass>`
   - classpath 预生成：`./mvnw -pl <模块> dependency:build-classpath -Dmdep.outputFile=target/jvm-classpath.txt`
   - **必须触发完整业务链**（发任务→消费→解析→上报），agent 只记录实际执行过的调用
   - agent 插桩使启动从 0.2s 膨胀到 60s+：等待窗口放宽（~120s）；进程退出必须优雅
     （SIGTERM + ≥30s wait）否则配置不落盘
2. **验证产物**：GraalVM 25 agent 输出**单文件统一格式** `reachability-metadata.json`
   （不再是旧版 reflect/jni/resource 分文件）；grep 确认缺口在内
   （如 `java.awt.GraphicsEnvironment`、`org/apache/pdfbox/resources/afm/`）
3. **落位**：整文件复制到 `src/main/resources/META-INF/native-image/<group>/<artifact>/`，
   native-image 扫描 classpath 自动加载，build 脚本零改动；配置随版本库管理，
   代码演进后重跑第 1 步覆盖采集
4. **重建 + 复跑冒烟闭环**；agent 采集是兑底手段——静态可注册的（契约 DTO）
   仍优先 RuntimeHints 显式登记，可维护性更好

冒烟脚本编写教训（R1 过程实证）：
- 成败标记必须源自真实代码的日志语句（grep 源码核对），勿凭记忆猜测——
  worker 的 publishFailed 静默发布无日志，猜的标记永远不命中
- 本地 HTTP 桩：Java RestClient 可能走 chunked（无 Content-Length），须按块协议读请求体；
  中文分流标记词同时匹配原始 UTF-8 与 `\uXXXX` 转义两种形态
- 本地 broker（podman 容器 lavinmq，RabbitMQ 兼容）：管理台清队列用
  `DELETE /api/queues/%2F/{name}/contents`（无 `/purge`，404）

## 五、工作模式：先修改后执行

1. 一次性改完所有相关文件（脚本/POM/yml/CI/README 同步，防止文档与结构再次漂移）
2. 语法层验证：`bash -n`（shell）、`python3 -c "import yaml; yaml.safe_load(open(...))"`（CI yaml）
3. 单元层验证：受影响模块 `./mvnw -pl <模块> -am test`
4. 端到端验证：完整跑 `POSTGRES_PASS=... bash stock-calculator-main/build-native.sh`（冒烟需真实连库），
   再对二进制做 HTTP 冒烟（stock-calculator-main/smoke-curl.sh 验证 token 门禁信封）
5. 改脚本时的两条铁律：native-image 失败要打日志尾部并 exit 1；
   冒烟测试失败要 exit 1（不许 `|| true` 掩盖）

## 六、与其他技能的关系

- ELF 已产出但冒烟/运行期崩溃（反射/类/资源缺口、静默失效）→ `stock-calculator-native-runtime-metadata`（若未被自动加载，直接读 `~/.agents/skills/stock-calculator-native-runtime-metadata/SKILL.md`，症状清单见其 §一）
- 终端命令限制（禁 `${...}`/`$VAR`/过长 heredoc）见 `stock-calculator-workflow`：
  含 `$` 的脚本/文档内容一律用 `write_file` 首写 + `edit_file` 以段尾标记（如 `__CHUNK_END__`）分段续写，
  不要用 terminal heredoc 写含 `$` 的文件
- 实体/Service/TaskService 的编码规范见 `stock-calculator-backend-dev`
