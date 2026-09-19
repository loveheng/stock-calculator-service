---
dev-loop: lessons
format: v1
epic: global
total-merged: 5
last-merge: 2026-09-19
---

# stock-calculator-service 项目经验总结

（正文＝按模块归类的避坑规则 SSOT，由 /merge 从底部追加区归纳提升；正文行禁用 `- [` 开头，该前缀专属追加区）

## terminal/沙箱

terminal 沙箱下静默写命令（cat >> / sed -i / mkdir）报 exit 2 "Cannot set tty process group" 属 pty 退出伪故障，命令本体已执行成功；勿据 exit code 盲目重试（会重复写入），先读目标文件核验落盘结果再决定动作。(Ref: stock-common)

edit_file/write_file 落盘的 Java 文件被 Zed 格式化钩子整文件重排（import 重排 + 100 列换行风格，与代码库 125 列风格不一致），且偶发编辑未实际落上但文件已被重排 → 本仓库改代码一律走终端 sed/cat heredoc（绕开编辑器保存管线；含 $ 字面量的行用 python chr(36) 构造或按行号 sed 删除），写完以 git diff --stat 核对变更行数是否符合预期（远超预期 = 被重排，git checkout -- 恢复重做）。(Ref: misc)

## 测试环境

宿主机跑 mvnw 测试：.env 的 POSTGRES_URL/RABBIT_HOST 是 docker 网络内部主机名，宿主机解析不了（UnknownHostException: lavinmq / Unable to determine Dialect）→ 须覆盖 SPRING_PROFILES_ACTIVE=postgres + POSTGRES_URL=localhost:5432 + RABBIT_HOST/REDIS_HOST=localhost。(Ref: misc)

跑 data 集成套件（ResultPublisherTopologyTest 断言 result.ingest.q 深度）前先停本地 live 的 app 容器——测试消息会被秒吃导致断言失败；跑完恢复。(Ref: misc)

全量验证 -Dtest=!TaskServiceTest 在 contract 空测试模块报 No tests matching pattern（surefire 3.5.6 对排除模式同样按指定测试处理，缺省 failIfNoSpecifiedTests=true，-DfailIfNoTests 管不到它）→ 补 -Dsurefire.failIfNoSpecifiedTests=false 与 -DfailIfNoTests。(Ref: misc)

删除测试类后跑 surefire 报 ClassSelector resolution failed（junit discovery 崩溃）→ target/test-classes 残留旧 .class：源文件已删但 class 未清，测试发现期按类名扫描仍选中，加载时引用已删主类失败 → 删测试类后同步删 target/test-classes 下残留 class（或 clean）再跑 test。(Ref: task-unify)

改动 Repository/接口后跑测试报 Mockito "Cannot instrument interface ... Unresolved compilation problem: List cannot be resolved"（源码其实缺 import）→ IDE 的 ECJ 把带编译错误的 .class 写进 target/classes，Maven 增量编译见 .class 比 .java 新而跳过重编，javac 从未真正编译该文件 → 先补真实编译错误再 clean test，勿信增量产物。(Ref: misc)

## native（GraalVM）

native 模块注解属性禁止运行期 SpEL 引 bean 属性：@RabbitListener 队列名写 `#{bean.name}` JVM 可跑、native 启动即 "Expression parsing failed"（SpEL 属性访问无反射元数据，Queue.getName() 不在可达性集合）。匿名队列用 @QueueBinding 声明式：@Queue(value="", durable="false", exclusive="true", autoDelete="true")（内部即 AnonymousQueue），一切注解属性用常量。(Ref: misc)

新增 contract message DTO 后 native 消费端报 InvalidDefinitionException "no delegate- or property-based Creator"（JVM 正常，仅 native 必现）→ Spring AOT 推断不了消费端手工 readValue/convertValue 的类型，DTO 必须登记 ContractRuntimeHints.DTO_TYPES（嵌套类随宿主 getNestMembers 自动覆盖），2026-09-19 PullConfigPayload/PullHeartbeatPayload 漏登记即运行期崩 → data 已新增 ContractRuntimeHintsCoverageTest 包扫描守卫：message 包每个具体类必须已注册，人工约定升级为构建期断门。(Ref: task-unify)

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

## 部署/冒烟

IDE 起服务与容器/脚本起服务环境不一致：角色开关忘配则队列 0 消费者静默积压（历史坑 DATASVC_WORKER_ENABLED 缺省 off；v2.5 后 yml 与 native 构建期均钉死生产恒 true，教训收敛为「冒烟前核对配置差异」）；IDE 注入的 JVM 代理参数(proxyHost)会拦外部 API 调用；IDE 控制台日志无法文件化，排障改走 MQ 管理 API(/api/queues 看 consumers/messages/unacked) + docker exec psql → 冒烟前先核对角色开关与出口网络。(Ref: copilot-memory)

SSE /search/composite 检索正常出 meta 后紧跟 error 503「综合摘要服务暂不可用」→ LlmChainRouter 全渠道不可用回落 fallback 模板：运行进程 GEMINI_API_KEY 缺失（isAvailable 判否跳过）+ GROQ_API_KEY 占位符假 key（非空校验通过、调用期 401）→ composite 503 先查运行进程实拿的渠道 key（/proc/进程号/environ）；配置修复以进程实拿环境为准，勿信 .env 文件字面。(Ref: misc)

main 由 IDE 托管时 AI 出口代理不稳定，且 JVM 全局代理会劫持 docker 主机名的内部 HTTP（lavinmq 管理 API 503 → BROKER_UNREACHABLE 假告警）→ 代理经 JAVA_TOOL_OPTIONS 注入时 http.nonProxyHosts 必须排除内部主机名（默认白名单仅 localhost|127.*|[::1]，容器别名 lavinmq 不覆盖）→ 重启走 /tmp/scs-main-launch.py（快照 pid 的 environ/cmdline 用完即删，.env 覆盖同名变量，setsid 拉起，日志 /tmp/scs-main.log）；main 已脱离 IDE 托管，IDE 再起需自配代理 VM 参数与最新 .env。(Ref: misc)

## JPA/持久层

公告关键词检索带日期 500（could not determine data type of parameter $7），不传日期正常 → JPQL (:param IS NULL OR col>=:param) 谓词：null 绑定经 setNull 带类型 OK，非 null LocalDate 经 setObject 无类型下发，PG 对「参数 IS NULL」裸位置推断不出类型（42P18）；「列 IS NULL OR 列比较参数」形态安全（参数处于比较上下文可推断）→ 日期条件禁止写 :param IS NULL 谓词，Service 按条件成立与否分流到带/不带谓词的方法（AnnouncementRepository 四变体先例）；全仓同类排查仅 ClsArticleRepository 同形但 Long typed 绑定实测安全。(Ref: misc)

## 追加区

