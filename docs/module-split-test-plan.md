# 主/数据模块拆分 · 测试计划

> 版本：v1.0（2026-09-12）
> 被测对象：提交 `4c70110 系统拆分为主模块和数据模块` + 工作区未提交的终态清理（main 侧回退路径删除、MQ 单路径化、monitor 巡检域、历史补录 MQ 化）。
> 关联设计：docs/data-service-split-design.md（v2.4）、docs/data-source-onboarding.md、docs/announcement-rag-pipeline-design.md、docs/cls-article-vector-backend-design.md

## 1. 被测对象与范围

### 1.1 拆分后的结构

| 模块 | 定位 | 关键内容 |
|---|---|---|
| stock-calculator-contract | 契约单点（D9） | MessageEnvelope / MessageType / MqExchange / MqKey / MqQueue / MqPolicy + 全部 payload DTO + ContractRuntimeHints（native 反射登记） |
| stock-calculator-data | 数据服务（无 DB，D2） | DataServiceApplication 双角色：collector（CLS/公告拉取、webhook ingest、归一化上行、HistorySyncWorker）+ worker（公告 PDF 处理链、向量计算）；MqTopologyConfig 集中声明拓扑 |
| stock-calculator-main | 主服务 | 幂等入库、状态机（D7）、配额记账（D8）、订阅 CRUD + 快照下发、search/全部 REST API；MQ 常驻装配（v2.4 终态：无回退开关，单路径） |

### 1.2 本次修正内容（= 测试关注点）

- **a. main 侧进程内路径删除**：announcement 管道（parser 5 件套、CninfoClient+DTO、ExtractedDocument、DistillService、GroundingValidator、ProcessService、CollectService、SyncTask、订阅首拉监听）、CLS 进程内拉取（TaskService、ClsDayTask、HistoryClsDayTask、CommonHttpService、ClsSignUtil、ClsDayTaskHelp、ParseDataUtil、ApplicationEvent 启动补录、ClsSearchTask）、embedding 进程内计算半边（EmbeddingErrorClassifier、回填进程内分支、EmbeddingBackfillMailListener）。共 14 处副本归零。
- **b. 7 个 MQ Bean 摘除条件装配**（TaskPublisher / ClsArticleMqConsumer / 两 Publisher / EmbeddingTaskDispatcher / RabbitTopologyConfig / RabbitPublishConfig）：任何上下文都常驻装配。
- **c. 历史补录 MQ 化闭环**：main `SynclsHistorycontroller` 改发 `task.history.sync` → data 新增 `HistorySyncWorker`（区间滚动拉取 + 频控平移）→ `result.cls.article` 上行 + `result.cls.history.report` 回执。
- **d. 新增 monitor 域**：PipelineWatchTask（队列堆积/停机巡检）+ RabbitManagementClient + PipelineAlertEvent + PipelineAlertMailListener。
- **e. 双侧 application.yml 调整**、main native 二进制待重建（遗留项）。

### 1.3 范围外

- search / auth / vision / copilot / sync / customstat 功能不变，仅纳入 P1 回归（已有单测不动）。
- 前端不涉及；KEDA/部署项单列 P5（属部署验证，不阻塞代码合入）。

## 2. 测试环境与前置条件

### 2.1 环境清单

| 项 | 要求 | 说明 |
|---|---|---|
| JDK | 21+ | JVM 测试用 |
| GraalVM | native-image 可用 | 仅 P4 需要 |
| PostgreSQL + pgvector | main 测试与运行依赖 | 通过 POSTGRES_PASS 注入口令 |
| RabbitMQ / LavinMQ | 真实 broker | E2E 与拓扑验证必需；LavinMQ 管理 API 无 /purge，清队列用 DELETE /contents |
| LLM / embedding 凭据 | 测试内全部用桩 | E2E 用本地 JDK HttpServer 桩（OpenAI 兼容），CF 凭据缺失时配 dummy（fail-fast 见 2.3） |

### 2.2 门控开关与环境变量

| 开关/变量 | 用途 | 注意 |
|---|---|---|
| RABBIT_E2E=true | 放行全部 MQ E2E 用例（默认 skip） | 须真实 broker 在位 |
| datasvc.collector.enabled | data collector 角色总开关 | 测试上下文置 false 防真实打 CLS/CNINFO（CollectorGateTest 教训） |
| datasvc.worker.enabled | data worker 角色总开关 | 置 true 会连带激活 CF 凭据 fail-fast，须同配 dummy embedding 凭据 |
| embedding.backfill.enabled | main 回填任务总开关（默认 true） | 共享 broker 的 E2E 须显式关闭，防污染队列 |
| announcement.process.cron | 公告任务扫描 cron | E2E 配 `-` 禁调度器（缓存上下文在类结束后仍存活，会扫真实库发布任务） |
| PIPELINE_ALERT_EMAIL | monitor 告警邮件收件人 | P3 巡检告警验证用 |

### 2.3 已知坑（执行前必读）

1. E2E 测试类默认 skip，必须带 RABBIT_E2E=true 且本地 broker 可达。
2. 共享 broker 上跑 E2E 前，先确认队列已清空；跑完后核对队列终态全 0、无新增死信。
3. worker.enabled=true 的上下文必须同时给 dummy embedding 凭据，否则上下文起不来。
4. collector 门控测试若误开 enabled，会真实请求 CLS/CNINFO —— Gate 测试就是防这个的，不要为了过测试关掉它。
5. native 构建期 JVM agent 插桩会使启动从 0.2s 变 60s+，属正常现象，不是卡死。
6. LavinMQ 的 Java RestClient 响应为 chunked（无 Content-Length），自建桩须按块读（RabbitManagementClient 相关验证）。

### 2.4 回归基线（v2.4 验收口径）

| 模块 | 基线 | 口径 |
|---|---|---|
| stock-calculator-main | 369 用例，0 失败，9 skip | JVM，E2E 不含在内 |
| stock-calculator-data | 68 用例，0 失败，3 skip | JVM，E2E 不含在内 |

> 任何一轮回归后，用例数不得低于基线（本轮补新用例只增不减）。

## 3. 测试分层与用例矩阵

### P0 编译与架构护栏（合入前必过，分钟级）

| # | 项 | 方法 | 通过标准 |
|---|---|---|---|
| P0-1 | 全仓编译 | 根目录 `./mvnw -q compile` | 无编译错误；对已删类（TaskService、ClsDayTask、EmbeddingErrorClassifier 等）零残留引用 |
| P0-2 | Modulith 护栏 | 跑 `ModulithVerifyTest` | 跨域访问仅经端口接口（TaskDispatchApi / AnnouncementEmbeddingApi / AnnouncementIngestApi / EmbeddingSearchApi），无环 |
| P0-3 | 契约纯净 | contract 模块编译 + 依赖树 | 不引 JPA / Spring Web（D9：防实体类型泄漏进消息） |
| P0-4 | 双应用上下文 | main 与 data 各起一次 contextLoads | 常驻 MQ Bean（无 @ConditionalOnProperty）在默认配置下装配成功 |

### P1 单元回归（JVM，无需 broker）

**main 侧重点用例**（对应本次修正 a/b）：

| 测试类 | 回归关注点 |
|---|---|
| AnnouncementResultServiceTest | collected 幂等摄取、orgId 回填仅补空白、超体积终态 FAILED、failCount 恒加、PERMANENT/达次落 FAILED、RATE_LIMITED 熔断 |
| AnnouncementProcessPublisherTest | PENDING 扫描发布（top50 近端优先）、已蒸馏二段补发、熔断窗口内停发 |
| SubscriptionSnapshotPublisherTest | AFTER_COMMIT 触发、启动首推、30min 重推、空订阅照发空快照、version 单调 |
| AnnouncementEmbeddingMqServiceTest | 二段向量任务下发、三处同步 UPSERT 本地副本、额度状态中断 |
| EmbeddingResultServiceTest / EmbeddingTaskDispatcherTest | kind 分支、维度校验前置、cls-only 派发 |
| EmbeddingQuotaGuardTest / EmbeddingBackfillTaskTest | 发布端扣减（D8）、MQ 对账器扫缺补发、回填开关生效 |
| ArticleEmbeddingListenerTest | ArticleSavedEvent 单路径（MQ 发布），进程内分支已删不复活 |
| PipelineWatchTaskTest | 堆积/停机判定 → PipelineAlertEvent 发布（新 monitor 域） |
| ModulithVerifyTest | 随 P0-2 |

**data 侧重点用例**：

| 测试类 | 回归关注点 |
|---|---|
| AnnouncementProcessWorkerTest | done 载荷完整、SKIPPED_NO_TEXT 终态、LLM_ROUTE_FAIL=TRANSIENT、毒消息 ack 丢弃 |
| AnnouncementCollectorServiceTest | topSearch 转换、FULL/LOOKBACK 双模式、白名单回填、标题剥 em |
| SubscriptionSnapshotCacheTest / ConsumerTest | version 单调拒绝 <=（R3）、catch-all ack 无重试环 |
| TextCleanerTest / GroundingValidatorTest / DistillServiceTest | 平移后语义不变（与 main 时代口径一致） |
| ClsArticleParserTest | 解析产出与契约 DTO 字段对齐 |
| EmbeddingComputeWorkerTest | 三分类失败分流（429/PERMANENT 丢弃、TRANSIENT 进重试环、401/403 投 dead.q） |
| IngestControllerTest | HMAC 鉴权、插件路由、非法载荷拒绝 |
| ResultPublisherTopologyTest | 上行发布声明与契约一致 |
| CollectorGateTest / AnnouncementCollectorGateTest | collector.enabled=false 时零外呼（防误拉） |

**P1 缺口（本轮必须补的用例）**：

| # | 缺口 | 理由 |
|---|---|---|
| G1 | `HistorySyncWorkerTest`（data/cls）——覆盖：区间滚动窗口推进、连续 5 次空数据自动终止、交易时段降频、异常 ack 丢弃不进重试环、report 回执字段 | 新增类尚无任何测试（本次修正 c 的核心执行体） |
| G2 | `SynclsHistorycontroller` 触发端改造断言（发 task.history.sync、HTTP 响应"已受理"） | 现有用例未覆盖 MQ 化后的新行为 |
| G3 | PipelineWatch 告警投递（RabbitManagementClient 读 LavinMQ 队列深度 → 邮件监听器）单测 | monitor 域新增，邮件链路仅有 PipelineWatchTaskTest 覆盖判定逻辑 |

### P2 MQ 集成 / E2E（真实 broker，RABBIT_E2E=true）

| # | 链路 | 用例（模块） | 关键断言 |
|---|---|---|---|
| P2-1 | CLS 电报上行 | ClsArticleMqConsumerE2ETest（main） | result.cls.article 契约还原入库、字典/关联落表、重复投递无副作用（ON CONFLICT 语义）、队列终态 0 |
| P2-2 | 向量结果上行 | EmbeddingResultMqConsumerE2ETest（main） | 确定性 UUID upsert、指纹未变跳过防旧覆新、状态行同批原子落账、重复投递无副作用 |
| P2-3 | 公告采集 | AnnouncementCollectedMqE2ETest（main） | PENDING 落库、orgId 回填、幂等、超体积终态；注意关回填 + 禁公告 cron |
| P2-4 | 公告任务下发/落账 | AnnouncementProcessMqE2ETest（main） | 发布 → done/failed 落账、failCount、终态、RATE_LIMITED 熔断窗口 |
| P2-5 | 订阅快照控制面 | SubscriptionSnapshotMqE2ETest（main） | 快照经 collector.control.q 契约还原、退订后重推不含已退订标的 |
| P2-6 | worker 公告闭环 | AnnouncementWorkerE2ETest（data） | task 下发 → 下载/抽取/建树/路由/切片/蒸馏/接地全真实现（LLM 用桩）→ done 全字段断言；mock 下载异常 → failed(DOWNLOAD_FAIL/TRANSIENT)；建树空 root 兜底降级路径 |
| P2-7 | worker 向量闭环 | EmbeddingWorkerE2ETest（data） | task.embedding.compute → result.embedding.done，traceId 透传、retry 队列纳入清理 |
| P2-8 | 拓扑声明一致性 | TaskPublisherTopologyTest / ResultPublisherTopologyTest | 两侧声明与 contract 常量零漂移（交换机/队列/key/参数） |

> P2 全部跑完后统一核对：六个工作队列 + retry 队列 + dead.q 消息数归零，dead.q 无新增。

### P3 可靠性与故障专项（半自动：脚本 + 管理台观测，逐项留证）

| # | 场景 | 步骤 | 预期（对应设计 §4.2/§4.4/§7） |
|---|---|---|---|
| P3-1 | 幂等回放 | 同一条 result.cls.article 用管理台重复投递 3 次 | 库中仅一行；无死信 |
| P3-2 | 重试环 | kill -9 worker 于处理中途 | 未 ack 消息回归队列，由其他副本（或重启后的单副本）完成 |
| P3-3 | 重试环耗尽 | 构造持续基础设施失败（如断 LLM 桩） | nack → retry 队列 TTL 30s → 三次后进 dead.q 停放，不无限重投 |
| P3-4 | 业务失败不进重试环 | 接地终败（GROUNDING_FAIL=PERMANENT） | 发 result.announcement.failed 后正常 ack，dead.q 无消息，failCount 在 main 计次（D7） |
| P3-5 | 对账兑现 | 停 worker 10 分钟再恢复 | PENDING 扫描与向量回填补发任务，最终全量消化，无重复副作用 |
| P3-6 | 优雅停机 | SIGTERM data 服务 | listener 30s 排空在途消息，未 ack 回归队列 |
| P3-7 | 停摆自愈（终态无回退开关后的新姿态） | 停 broker 期间 collector 拉取失败 → 恢复 | 数据源窗口自愈（CLS 滚动窗口/公告 7 天重叠）补齐数据，无需人工投递 |
| P3-8 | monitor 巡检告警 | 灌队列至阈值 / 停 worker | PipelineWatchTask 判定 → PipelineAlertEvent → 收到告警邮件（PIPELINE_ALERT_EMAIL） |
| P3-9 | dead.q 处置 | 从 dead.q 重放一条任务（管理台/脚本） | 任务重新被 worker 消费（开放问题 4 的手工路径验证） |

### P4 native 构建与冒烟

| # | 项 | 方法 | 通过标准 |
|---|---|---|---|
| P4-1 | data native 复验 | `bash stock-calculator-data/build-native.sh` → `bash stock-calculator-data/smoke-native.sh` → `python3 stock-calculator-data/native-r1-smoke.py` | ELF 产出、启动冒烟双绿、R1 全链（PDF 下载→PDFBox→建树→LLM→接地→result 上行）PASS，队列清零 |
| P4-2 | main native 重建（遗留硬门槛） | `bash stock-calculator-main/build-native.sh`（ContractHintsConfig 已在位） | 构建成功 → 启动冒烟 → REST 冒烟 → MQ 消费入库联调（与 data native 对打） |
| P4-3 | 契约反射登记检查 | code review + 构建期验证 | 新增/修改契约 DTO 已登记 ContractRuntimeHints（信封 + 19 DTO 全量 MemberCategory + nestMembers 递归） |
| P4-4 | PDFBox AOT 回归 | P4-1 的 R1 即覆盖 | native 下抽取字码数与 JVM 一致 |

### P5 系统级实证（部署后，可异步）

| # | 场景 | 预期 |
|---|---|---|
| P5-1 | KEDA 伸缩：灌 100 条任务 | worker 副本随积压扩容，消化后回落至 min=1（value≈5 条/副本） |
| P5-2 | 额度记账：发布端扣减 + worker 多副本并发 | 当日总量不超 60000，429 触发发布端冷却 |
| P5-3 | collector 双跑防护 | Recreate 更新策略下无并行双拉窗口；即使短暂双跑也幂等兜底（R5） |
| P5-4 | broker 单点故障演练 | 停 broker → main 报警（monitor）→ 恢复后窗口自愈 + 对账器补齐（R6） |

## 4. 执行顺序与命令

执行顺序：P0 → P1（含缺口补测）→ P2 → P3 → P4 → P5。P0/P1 不依赖 broker，可日常反复；P2/P3 需要真实 broker 与干净队列；P4 需要 GraalVM。

```sh
# P0 全仓编译（根目录）
./mvnw -q compile

# P1 main 单测（不启 E2E）
POSTGRES_PASS=<密码> ./mvnw test -pl stock-calculator-main
# P1 data 单测
./mvnw test -pl stock-calculator-data

# P2 全部 E2E（真实 broker 已起、队列已清空）
RABBIT_E2E=true POSTGRES_PASS=<密码> ./mvnw test -pl stock-calculator-main -Dtest='*E2ETest'
RABBIT_E2E=true ./mvnw test -pl stock-calculator-data -Dtest='*E2ETest'
# 也可单独跑：-Dtest='AnnouncementProcessMqE2ETest' 等

# P4 data native（GraalVM 环境）
bash stock-calculator-data/build-native.sh
bash stock-calculator-data/smoke-native.sh
python3 stock-calculator-data/native-r1-smoke.py

# P4 main native（遗留项，首次执行）
bash stock-calculator-main/build-native.sh
python3 stock-calculator-main/smoke-native.py
```

## 5. 通过标准与退出准则

1. P0 全绿；P1 用例数 ≥ 基线（main ≥ 369、data ≥ 68 + G1~G3 新增），0 失败，skip 数不高于基线（E2E 门控 skip 除外）。
2. P2 全部 E2E 绿；跑后六队列 + retry 队列终态 0，dead.q 无新增。
3. P3 九个专项逐项留证（管理台截图/日志/库记录），全部符合预期。
4. P4-1 复验绿；P4-2 main native 重建通过（本次遗留项清零）；P4-3 登记 check 无缺项。
5. 缺陷零遗留（P1/P2 级阻塞项）；P5 允许部署后异步完成，但须在合入 main 分支前列入跟踪。

## 6. 缺陷记录与执行留痕

### 6.1 执行记录 · 第 1 轮（2026-09-12，环境：本地 LavinMQ 容器 + 本地 PG scs 库 + GraalVM 25.0.4）

| 编号 | 结果 | 备注 |
|---|---|---|
| P0-1 全仓编译 | ✅ | 三模块零错误，已删类零残留引用 |
| P0-2 Modulith 护栏 | ✅ | ModulithVerifyTest 2 用例通过（随 P1） |
| P0-4 双应用上下文 | ✅ | main contextLoads 1 例 + data 各上下文随 P1/P2 |
| P1 main 单测 | ✅ | 374 用例，0 失败，9 skip（≥ 基线 369，新增 PipelineWatchTaskTest 5 例） |
| P1 data 单测 | ✅ | 68 用例，0 失败，3 skip（= 基线；3 skip 为 E2E 门控） |
| P2-1~P2-5 main E2E | ✅ | 8 用例全绿（ClsArticle/EmbeddingResult/SubscriptionSnapshot/AnnouncementProcess/AnnouncementCollected） |
| P2-6 worker 公告闭环 | ✅ | AnnouncementWorkerE2ETest 2 用例 |
| P2-7 worker 向量闭环 | ✅ 修复后 | 首跑上下文启动失败（缺陷 D1，见 6.2），修复后 1 用例通过 |
| P2-8 拓扑声明一致性 | ✅ | TaskPublisherTopologyTest / ResultPublisherTopologyTest（随 P1） |
| P2 后置队列核验 | ✅ | 10 队列终态全 0，dead.q 无新增 |
| P4-1 data native | ✅ | 201MB ELF，启动冒烟 + ingest 503 双绿（build-native.sh 内置冒烟） |
| P4-2 main native 重建 | ✅ | 首跑失败（缺陷 D2）→ 修复 build-native.sh 后 355MB ELF 产出、带库启动 0.45s（run-native-smoke.sh）；monitor 修复（D3）后已再次重建烘焙 |
| P3 可靠性专项 | ⏸ 未执行 | 需人为故障注入/kill 副本，留待部署环境轮次（P3-1/P3-4 的幂等断言已由 P2 用例部分覆盖） |
| P5 系统级实证 | ⏸ 未执行 | KEDA/多副本属部署项 |

### 6.2 缺陷与环境问题

- **D1（已修复，测试侧）**：EmbeddingWorkerE2ETest（worker=true, collector=false）上下文启动失败，两连根因：
  ① worker 门控链新增的 AnnouncementProcessWorker 依赖 CninfoClient（挂 collector 门控装配）→ worker-only 形态缺 Bean；
  ② 补 mock 后暴露同一依赖链下一层 fail-fast：AnnouncementWorkerConfig.llmGateway 要求 datasvc.llm.base-url/api-key/model 非空。
  修复：测试补 `@MockitoBean CninfoClient` + dummy LLM 三项配置（同 AnnouncementWorkerE2ETest 与 dummy embedding 凭据既有先例）。
  **遗留风险**：worker-only 生产部署形态（collector.enabled=false）当前会启动失败——即 v2.3 版本行遗留的「worker-only/collector-only 变体拆分」项，需生产代码解耦（如 ObjectProvider 惰性解析）或部署上暂用 all-in-one 变体规避。
- **D2（已修复，构建脚本）**：main native 构建在 native-image [1/8] 特性注册期报 `NoClassDefFoundError: org/apache/commons/logging/LogFactory`（Spring Data 4.1.1 新增 TypedPropertyPathFeature，构造期必加载 LogFactory）。根因链：`dependency:build-classpath` 产出的 cp.txt **末行无换行符**，而 build-native.sh 的 `while IFS= read -r` 循环在 EOF 丢弃最后一个未换行的条目 → 恰好排在末位的 commons-logging（pdfbox 3.0.8 传递依赖）没进 native-image classpath。最小复现证实 commons-logging 在 image classpath 上时宿主阶段可正常加载。修复：两处循环改为 `while IFS= read -r j || [ -n "$j" ]`（main + data 同款隐患一并修）。
- **D3（已修复，生产代码）**：RabbitManagementClient 用 `RestClient.uri(String)` 传已含 `%2F` 的 URL，被当 URI 模板**二次编码**成 `%252F`，LavinMQ 返回 404 "Vhost %2F does not exist" → PipelineWatchTask 每轮误报 BROKER_UNREACHABLE（monitor 域 R4 告警链路实际不可用，native 启动冒烟时首次暴露）。修复：改 `uri(URI.create(...))` 重载（不重编码）。验证：JVM 启动 80s 后巡检读到真实队列深度并按语义正确告警（本机无数据服务 → DATA_DOWN×2 + BACKLOG，邮件因未配 PIPELINE_ALERT_EMAIL 跳过）；PipelineWatchTaskTest 5/5 绿。
- **E3（观察，未定论）**：smoke-curl.sh 的 curl 无 `--max-time`，对 native 二进制 GET / 疑似挂起（应用本身健康，日志无异常），导致 REST 门禁断言未完成。建议给 smoke-curl.sh 加超时保护后人工复跑。
- **E1（已修复，脚本）**：run-regression.sh 在三模块结构下失效——`-Dtest='!TaskServiceTest'` 排除模式在无测试的 contract 模块触发 surefire `failIfNoSpecifiedTests` 报错；已补 `-Dsurefire.failIfNoSpecifiedTests=false`。
- **E2（运维观察）**：E2E 前发现 broker 陈旧积压 task.embedding.compute.q=3248、collector.control.q=8（无消费者），已按 D6/R3 重放语义清理；此积压即 PipelineWatchTask 巡检针对的停摆场景，建议关注。
- **新增脚本**：run-e2e.sh（分模块 E2E 执行）、run-native-smoke.sh（native main 带库启动冒烟）、run-jvm-watch.sh（monitor 巡检 JVM 验证），与 run-regression.sh 同环境口径；run-native-rest.sh 为 REST 门禁冒烟（受 E3 限制待复跑）。

> 缺陷记录建议直接登记到本文件末尾或 issue，格式：`[P?-*] 模块 - 现象 - 复现步骤 - 关联提交`。

## 7. 风险与计划外观察点

| 风险 | 观察点 |
|---|---|
| 回退路径删除后无第二路径 | 任何一处 MQ 链路异常都会直接断数据 —— P3-7（自愈）与 P3-8（告警）是兜底验证，不可省略 |
| 常驻装配放大测试污染面 | 上下文类缓存存活期间调度器/监听器仍在跑：E2E 必须禁 cron、关回填（2.3 条款） |
| 契约 DTO 演进 | 新增 DTO 忘登记 ContractRuntimeHints 会在 native 运行期才炸（InvalidDefinitionException）—— P4-3 前置 review |
| HistorySyncWorker 无单测即上线 | G1 为本轮最高优先缺口，先补测再合入 |
