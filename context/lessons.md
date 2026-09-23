---
dev-loop: lessons
format: v1
epic: global
total-merged: 7
last-merge: 2026-09-22
---

# stock-calculator-service 项目经验总结

（正文＝按模块归类的避坑规则 SSOT，由 /merge 从底部追加区归纳提升；正文行禁用 `- [` 开头，该前缀专属追加区）

## terminal/沙箱

terminal 沙箱下静默写命令（cat >> / sed -i / mkdir）报 exit 2 "Cannot set tty process group" 属 pty 退出伪故障，命令本体已执行成功；勿据 exit code 盲目重试（会重复写入），先读目标文件核验落盘结果再决定动作。(Ref: stock-common)

edit_file/write_file 落盘的 Java 文件被 Zed 格式化钩子整文件重排（import 重排 + 100 列换行风格，与代码库 125 列风格不一致），且偶发编辑未实际落上但文件已被重排 → 本仓库改代码一律走终端 sed/cat heredoc（绕开编辑器保存管线；含 $ 字面量的行用 python chr(36) 构造或按行号 sed 删除），写完以 git diff --stat 核对变更行数是否符合预期（远超预期 = 被重排，git checkout -- 恢复重做）。(Ref: misc)

## 依赖/构建

新依赖编译期报「无法访问 org.ta4j.core.Bar / 错误的类文件版本 69.0, 应为 65.0」→ 依赖 jar 字节码基线高于工具链 JDK（ta4j 0.22.8+ 实测 class v69=Java 25，0.17=v55=Java 11）→ 引第三方依赖前用 unzip -p jar 类路径 | od -An -j6 -N2 -d 查 major version 选兼容的最高版本（小端读数 17664=0x4500 即 v69），钉版并注释原因；报错形态只有「无法访问」一句时先怀疑字节码版本而非 API 变更。(Ref: mcp-service)

Boot 4 服务启动报 No qualifying bean RestClient.Builder（构造注入处全挂，且服务从未真实启动过所以一直没暴露）→ Boot 4 拆模块：spring-boot-starter-web（spring-ai starter 传递）不含 spring-boot-restclient 模块，mcp 模块能拿到该 Bean 是靠 openai model starter 传递 → webmvc/restclient 一律 Boot 4 标准 starter 显式声明；main RestClientConfig「Boot 4 不再自动配置 RestClient.Builder」注释为过时结论（4.1.1 实证 RestClientAutoConfiguration 提供 Bean，main 只是缺 starter）。(Ref: mcp-blogger-kb)

## 测试环境

宿主机跑 mvnw 测试：.env 的 POSTGRES_URL/RABBIT_HOST 是 docker 网络内部主机名，宿主机解析不了（UnknownHostException: lavinmq / Unable to determine Dialect）→ 须覆盖 SPRING_PROFILES_ACTIVE=postgres + POSTGRES_URL=localhost:5432 + RABBIT_HOST/REDIS_HOST=localhost。(Ref: misc)

跑 data 集成套件（ResultPublisherTopologyTest 断言 result.ingest.q 深度）前先停本地 live 的 app 容器——测试消息会被秒吃导致断言失败；跑完恢复。(Ref: misc)

全量验证 -Dtest=!TaskServiceTest 在 contract 空测试模块报 No tests matching pattern（surefire 3.5.6 对排除模式同样按指定测试处理，缺省 failIfNoSpecifiedTests=true，-DfailIfNoTests 管不到它）→ 补 -Dsurefire.failIfNoSpecifiedTests=false 与 -DfailIfNoTests。(Ref: misc)

删除测试类后跑 surefire 报 ClassSelector resolution failed（junit discovery 崩溃）→ target/test-classes 残留旧 .class：源文件已删但 class 未清，测试发现期按类名扫描仍选中，加载时引用已删主类失败 → 删测试类后同步删 target/test-classes 下残留 class（或 clean）再跑 test。(Ref: task-unify)

改动 Repository/接口后跑测试报 Mockito "Cannot instrument interface ... Unresolved compilation problem: List cannot be resolved"（源码其实缺 import）→ IDE 的 ECJ 把带编译错误的 .class 写进 target/classes，Maven 增量编译见 .class 比 .java 新而跳过重编，javac 从未真正编译该文件 → 先补真实编译错误再 clean test，勿信增量产物。(Ref: misc)

SSE 端点 E2E 订阅端 getResponseCode 死等、「先等握手再发事件」与「事件在握手后才发」互相死锁（订阅端收集恒空）→ SseEmitter 首个事件发送前不提交响应头，客户端拿不到 200 → emitter 注册发生在控制器同步段（请求处理即入注册表），固定等待后直接发事件，不等 HTTP 握手。(Ref: mcp-blogger-kb)

## native（GraalVM）

native 模块注解属性禁止运行期 SpEL 引 bean 属性：@RabbitListener 队列名写 `#{bean.name}` JVM 可跑、native 启动即 "Expression parsing failed"（SpEL 属性访问无反射元数据，Queue.getName() 不在可达性集合）。匿名队列用 @QueueBinding 声明式：@Queue(value="", durable="false", exclusive="true", autoDelete="true")（内部即 AnonymousQueue），一切注解属性用常量。(Ref: misc)

新增 contract message DTO 后 native 消费端报 InvalidDefinitionException "no delegate- or property-based Creator"（JVM 正常，仅 native 必现）→ Spring AOT 推断不了消费端手工 readValue/convertValue 的类型，DTO 必须登记 ContractRuntimeHints.DTO_TYPES（嵌套类随宿主 getNestMembers 自动覆盖），2026-09-19 PullConfigPayload/PullHeartbeatPayload 漏登记即运行期崩 → data 已新增 ContractRuntimeHintsCoverageTest 包扫描守卫：message 包每个具体类必须已注册，人工约定升级为构建期断门。(Ref: task-unify)

data 模块 --no-pkg 增量构建出的二进制缺新加的 kg worker：启动正常、其余 worker 消费者都在、kg 监听器零痕迹（日志无报错、队列未声明）→ 本地 target/spring-aot 产物早于 kg worker 源码，--no-pkg 复用陈旧 AOT，条件装配的新 bean 根本没进二进制 → data 新增 worker/角色代码后必须全量构建重生 AOT；验证法：find target/spring-aot/main/sources -name "*<Worker>*" 确认 bean 定义存在（对照 native-r1-smoke 只验启动不验功能，worker 缺失属静默失效）。(Ref: news-kg)

## 检索/引用核查

grep 工具全仓引用核查时 include_pattern 写 'docs/**' 全部落空，断言「零断链」实为假阴性（docs 重组断链漏检）→ glob 以含项目根的全路径为锚（如 'stock-calculator-service/docs/**'）；全仓核查免 include_pattern、用精确文件名模式逐项验证（规范条目另见 stock-calculator-docs §五）。(Ref: misc)

## 向量检索

向量检索带时间条件查不到新数据/窄时段返回空 → 召回 topK×4 后才在内存做 ctime 过滤，语料上量（48 万级）后新文章相关度排不进召回窗口、窄时段过滤完为空集 → 时间/来源条件必须下推 SQL（(metadata->>'ctime')::bigint 数值比较），窄窗高选择性再配 SET LOCAL hnsw.iterative_scan=relaxed_order 防丢召回（pgvector<0.8 无该参数需降级路径）。(Ref: misc)

公告向量 SQL 正常执行却检索恒空、回查零命中且零报错 → 写入侧 metadata.announcementId 误存内部自增 id（getId），读取方按 CNINFO 标识（getAnnouncementId）查询，键语义写读分裂 → metadata 键值必须以「读取方的查询口径」为准并加端到端断言（mock 层写读自洽测不出跨端键义错位）；插入前先人工核对一行 metadata 样本。(Ref: misc)

## 模块边界（modulith）

跨域 MQ 回传端口放业务域基包导致 Modulith 环（copilot→crawler 发任务 + crawler→copilot 回结果双向依赖，ModulithVerifyTest 拦截）→ 回传端口宿主规则：消费中枢所在域定义端口接口（crawler 基包），业务域只做实现，依赖单向（AnnouncementIngestApi 先例）；遇「域 A 发布、域 B 回传」先画模块依赖方向再定接口宿主。(Ref: copilot-memory)

## 配置文件

YAML 配置里写 Java 风格注释 /** ... */：编译期零报错（yml 不参与编译），Spring 上下文加载时 SnakeYAML 报 could not find expected ':'，且错误行号指向上百行外难定位 → 配置文件注释一律 #；上下文加载失败先怀疑最近改过的 yml 而非 Java；编辑工具的模糊匹配会「顺手」延续错误格式，改配置后跑一次 yaml.safe_load 自检。(Ref: copilot-memory)

## MQ 拓扑

live LavinMQ 存量队列参数与代码声明不一致（如缺 x-single-active-consumer）：RabbitAdmin.initialize 全量重声明遇 406 PRECONDITION_FAILED 即 channel 关闭，后续所有队列声明全失败，集成测试整体挂且报错指向首个冲突队列 → 队列参数以代码为 SSOT，测试环境直接删存量队列重建（业务消息是临时触发信号不丢数据），生产环境需迁移方案；拓扑改动后核对 mgmt API /api/queues 的 arguments 字段。(Ref: copilot-memory)

公告嵌入任务被 worker 消费但向量零落库、队列零积压零死信，表象「一切正常」→ EmbeddingComputeWorker kind 门控白名单缺 announcement（业务性 skip 后 ack 丢弃，静默黑洞：无 retry 无 dead 无错误痕迹）→ MQ 消费端新增消息 kind 必须与计算/处理端白名单同轮接线，两头各留 TODO 必挂；「队列干净」不等于链路通，排障盯消费端 skip 日志与端到端产物。(Ref: misc)

## LLM 网关

LLM 输出 JSON 被腰斩（UnexpectedEndOfInput expected close marker for Array）：OpenAI 兼容网关缺省 max_tokens 偏小，不显式传就吃网关隐式值，输出短时成功有迷惑性、输出长必截断 → 聊天补全请求恒显式传 max_tokens（LlmGatewayProperties.maxTokens=4096，仅放宽不改 EOS 停止）；解析失败日志带 rawTail 尾段，EOF 类异常看原文尾段才能定位。(Ref: copilot-memory)

LLM 结构化抽取的时间字段实测 100% 纯日期串（yyyy-MM-dd），ISO 容错解析只认 OffsetDateTime/Instant 致 kg_event.event_time 全量 null、时序时间轴空转 → 解析链缺 LocalDate 兜底分支（纯日期按 systemDefault 当日零点落锚）→ LLM 结构化输出的时间/数值字段解析按「最宽格式优先」编写，且动笔前先查证据实测格式，不能只认标准 ISO 全形。(Ref: news-kg)

OpenAI 兼容网关原生 REST 调 LLM 三连坑：JDK HttpClient 走 HTTP/2 报 "Request cancelled"、响应 content-type=application/octet-stream 无 charset 被 String 转换器拒收、长输出被默认 max_tokens 静默截断成非法 JSON → SimpleClientHttpRequestFactory 强制 HTTP/1.1 + byte[] 收包自行 UTF-8 解码 + 请求体显式 max_tokens + 分钟级读超时。(Ref: mcp-blogger-kb)

## 部署/冒烟

IDE 起服务与容器/脚本起服务环境不一致：角色开关忘配则队列 0 消费者静默积压（历史坑 DATASVC_WORKER_ENABLED 缺省 off；v2.5 后 yml 与 native 构建期均钉死生产恒 true，教训收敛为「冒烟前核对配置差异」）；IDE 注入的 JVM 代理参数(proxyHost)会拦外部 API 调用；IDE 控制台日志无法文件化，排障改走 MQ 管理 API(/api/queues 看 consumers/messages/unacked) + docker exec psql → 冒烟前先核对角色开关与出口网络。(Ref: copilot-memory)

SSE /search/composite 检索正常出 meta 后紧跟 error 503「综合摘要服务暂不可用」→ LlmChainRouter 全渠道不可用回落 fallback 模板：运行进程 GEMINI_API_KEY 缺失（isAvailable 判否跳过）+ GROQ_API_KEY 占位符假 key（非空校验通过、调用期 401）→ composite 503 先查运行进程实拿的渠道 key（/proc/进程号/environ）；配置修复以进程实拿环境为准，勿信 .env 文件字面。(Ref: misc)

main 由 IDE 托管时 AI 出口代理不稳定，且 JVM 全局代理会劫持 docker 主机名的内部 HTTP（lavinmq 管理 API 503 → BROKER_UNREACHABLE 假告警）→ 代理经 JAVA_TOOL_OPTIONS 注入时 http.nonProxyHosts 必须排除内部主机名（默认白名单仅 localhost|127.*|[::1]，容器别名 lavinmq 不覆盖）→ 重启走 /tmp/scs-main-launch.py（快照 pid 的 environ/cmdline 用完即删，.env 覆盖同名变量，setsid 拉起，日志 /tmp/scs-main.log）；main 已脱离 IDE 托管，IDE 再起需自配代理 VM 参数与最新 .env。(Ref: misc)

## JPA/持久层

公告关键词检索带日期 500（could not determine data type of parameter $7），不传日期正常 → JPQL (:param IS NULL OR col>=:param) 谓词：null 绑定经 setNull 带类型 OK，非 null LocalDate 经 setObject 无类型下发，PG 对「参数 IS NULL」裸位置推断不出类型（42P18）→ 日期条件禁止写 :param IS NULL 谓词，Service 按条件成立与否分流到带/不带谓词的方法（AnnouncementRepository 四变体先例）。(Ref: misc)

原生查询可空参数绑定行为随执行路径漂移（42P18 变体）：JVM 动态代理仅 temporal null untyped，native AOT（*__AotRepository）下 temporal/String null 均 untyped，只修实测炸点会在另一条路径换位置复发（kg 三段查询 JVM 修好 native 复发实证）→ 原生 SQL 的 IS NULL 位可空参数一律显式 CAST 定型（不按类型侥幸，temporal CAST AS timestamptz / keyword CAST AS text / id CAST AS bigint）；新原生查询落地当天用全 null 参数打一遍默认态接口，native 部署前在镜像里冒烟；全仓同类形态 ClsArticleRepository.searchByContentKeyword（Long）JVM 实测安全、native 未验待查。(Ref: news-kg)

新表 DDL 与实体字段漂移（kg_evidence 漏 created_at）只在运行期暴露：ddl-auto=none 下 Hibernate 不补列，断点一 DDL 试跑（BEGIN-ROLLBACK）只验 SQL 可执行不验实体逐列对齐，常规验证命令又排除 @SpringBootTest，集成层零覆盖 → 新表落 schema.sql 时以实体字段清单为基准逐列核对；用 -Dspring.jpa.hibernate.ddl-auto=validate 跑 contextLoads 做全库对齐审计（零改动复用现有测试），或正式启用 validate 让漂移启动期 fail-fast；已建表存量库用 ALTER TABLE ADD COLUMN IF NOT EXISTS 补列（CREATE TABLE IF NOT EXISTS 对存量表不生效）。(Ref: news-kg)

JPA 派生 deleteByXxx 的 DELETE 不立即执行（先 SELECT 实体、按 id 删除排队到 flush）：同一事务里 JdbcTemplate upsert 先写（同键旧行走 ON CONFLICT UPDATE 保留原 id），commit 时 Hibernate flush 才执行排队的按 id DELETE，把刚 upsert 的旧行连带删掉（forceResync 声称 upsert 684 行、库里只剩 ~330 且幸存行恰好是「resync 前不在库里的行」，三次实验头/尾交替互补）→ 同一事务内 JPA 实体删除与 JDBC 直写勿混用：删除改 JdbcTemplate 立即执行（或 deleteBy 后 flush 隔离）；「幸存行=本侧行」的互补模式=延迟删除吃掉新写入的强指纹。(Ref: mcp-service)

## MCP

MCP 探活把 tools/list 当会话首条消息：POST 返回 200/202 但 SSE 流零响应（服务端对未初始化会话按协议静默丢弃），而非法会话 404 / 非法报文 400 秒回，极易误判成服务端挂起；实测根因是 MCP 协议时序硬性要求 initialize → notifications/initialized → 之后才能发 tools/list 等请求，协议序错误的表现就是静默无响应 → 手工 curl 探活严格按三步协议走；请求合法却零响应先核对消息顺序再怀疑服务端；SSE 流探活用前台 curl + --max-time 兜底（后台任务延迟执行会污染时序判断）。(Ref: mcp-service)

字典镜像 JSON 键 isStib 与 Lombok 布尔字段 stib 错位：@Data 的 isStib() 在 Jackson 里属性名是 stib，且 Jackson 3 裸 new ObjectMapper() 默认 FAIL_ON_UNKNOWN_PROPERTIES=true，读侧逐行抛错被 fail-open 吞成「0 条载入」，写读双方单测各自自洽测不出跨端键义错位 → Jackson 3 注解包仍是 com.fasterxml.jackson.annotation（@JsonProperty 钉键名）；属性名错位+未知键失败叠加时表现为「静默空结果」，跨端 JSON 契约要有一侧用真实镜像样本做端到端断言。(Ref: mcp-service)

新增 @Tool 工具 bean 后 MCP 层报 "Tool not found" → McpToolConfig.toolObjects 是显式列举注册，非自动扫描，漏挂即 bean 存在但工具不可见 → 新增工具类必须同步加进 toolObjects，且验收前先 tools/list 对账。(Ref: mcp-blogger-kb)

## 追加区
- [公告向量化E2E] 确定性 UUID 向量行断言 expected 1 but was 0（改 metadata CNINFO 口径后变 5）➔ 确定性 UUID 以内部自增 id 生成，公告行删除重建后 id 漂移换新 UUID，而测试清理逻辑挂在「公告行存在」前提上，孤儿向量行跨轮累积无人删 ➔ 测试清理与断言必须用业务稳定锚（metadata CNINFO 标识）直删向量行，不依赖主行存在；确定性 UUID 锚选自增 id 天然抗不住行重建 (Ref: misc)
