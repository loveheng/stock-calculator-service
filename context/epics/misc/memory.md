---
dev-loop: memory
format: v1
epic: misc
total-merged: 7
last-merge: 2026-09-25
---

# misc：散修与小改动挂靠（常驻杂项 epic）

（协议见 dev-loop skill §3「杂项挂靠」：散修/小改动统一挂靠本 epic；长成大功能则 /bind 转正，届时此处归并只留一行索引，日志不搬家）

## CI/构建

- docker-image.yml = changes 检测（dorny/paths-filter）+ build-main/build-data 双 job；contract/根POM/mvnw 变更双端重建；data 模块 Dockerfile.native（ghcr.io/<repo>-data，8080/ingest）；main build-native.sh 需父POM+contract install（CI 冷缓存必挂）。（2026-09-12）
- v2.5 worker 变体退役：删 Dockerfile.worker、build-data-worker job、build-native.sh VARIANT 分支（二进制恒三角色全开）、compose data-worker 块。（2026-09-13）

## data 拓扑/设计（pull-loop + 日历任务）

- 日历型定时任务定案：拒绝 cron→per-message TTL 种子衰变（复活已否方案、违反设计不变量 3、长 TTL 种子不可撤销）；终态 = main 看门狗 CAS 认领 + work 队列一次性直发（data 侧无续种无 delay）；pull_task_config 增 schedule_mode/cron_expression/timezone/next_expected_time 四列，LOOP 行不落 next_expected_time（健康口径 = last_renew_time 新鲜度，所有者确认）。（2026-09-13）
- 首个日历任务 task.hello.world 落地（每日 07:00 Asia/Shanghai）；watchdog 拆双节奏（watch 30min + calendarClaim 60s CAS）；main 383 / data 79 用例绿。（2026-09-13）
- data application.yml 注释对齐 v2.5：worker/collector 开关注释改「生产恒 true + MQ 协议仲裁单消费者（task.history.sync SAC + 种子自愈）」、heartbeat 注释改 Dockerfile.native HEALTHCHECK；datasvc.worker.enabled 现仅测试装配隔离时置 false，DATASVC_WORKER_ENABLED 表述废弃。（2026-09-17）
- main 侧 cron 任务调度 DB 化早期迭代（app_task_config 表 + AppTaskHandler/AppTaskScheduler，2026-09-18）已被 task-unify 合表迁移收编（AppTaskScheduler 删除、任务行并入 pull_task_config CALENDAR 行），终态详见 task-unify memory。

## 转正索引

- data 单镜像多副本改造（v2.5，2026-09-13）已转正为 epic: data-single-image——决策/踩坑/验收随迁该 epic memory.md。

## skill 与 docs 体系建设

- docs/mcp/usage.md 使用手册新增（启停/客户端接入/工具速查/订阅源管理/离线加书/行情字典管理口/FAQ），README mcp 域索引同步过 lint。（2026-09-20）
- docs/ 整合为 8 功能域子目录（域内去前缀重命名）+ docs/README.md 总索引 + 24 篇补 frontmatter（全 active）；规范沉淀为 skill stock-calculator-docs（frontmatter/域落点/墓碑/§八写后 lint）。（2026-09-15）
- 后端编码规范 skill 定名 stock-calculator-backend-dev（原 cls-article-patterns 重构：事实指针化→workflow/service-index、3.3/3.4.3 去重、域枚举模式化、description 对偶化），全仓引用同轮同步为惯例。（2026-09-15）
- 体系 skill 强化：dev-loop audit 十项清单（新增第 10 项事实指针化抽查）、dev-guide §5 检测网同步、workflow 补「本地依赖服务」（compose 三件套/scs-net/.env 口令口径）、新增 .agents/prompts/task-template.md 任务派发模板。（2026-09-15）
- skill 体系手册落 docs/architecture/agent-skill-system.md：设计意图（六原则+分层图+裁决链）、skill 清单与触发、会话生命周期图、指令手册、场景速查、治理规则与反模式；v2 补 §1.5 冷启动（global 包无远端：拷贝兜底/推荐配远端 clone）、§1.6 IDE 兼容（非 Zed 手动读 SKILL.md 兜底）、§4.3 灾难恢复与记忆防腐；memo-collector 补录后共 12 skill、文档 8 处落点同步。（2026-09-15/16）
- skill 治理：dev-guide 项目数据解耦达成「换项目零编辑」（项目命令路由至 service-index 新增「命令速查」节，前端项目索引同补）；dev-loop audit 第 10 项扩为 skill 卫生抽查（事实指针化 + description 预算 ≤~250 目标/550 硬顶）；环境事实归一至 workflow「环境与工具链」（native-build/runtime-metadata 改单行指针）；dev-loop 增护栏总纲「求助人类优先」、§4 归并事故回滚纪律（严禁 AI 自修，git checkout 回滚）、§9 /help 指令。（2026-09-15）
- stock-common 归档核实：其未竟事项已失效——main 侧 8 处迁移过渡副本与门控开关（datasvc.mq.enabled 等）已由 ed73cad 代码精简删除，任务无对象，archive memory 已补核实注；native-build §一 模块清单过期点已点名上报（未擅动）。（2026-09-15）
- 新增全局 skill memo-collector：AI 回复/用户口述中的待办/风险/完成自动收集去重落盘——context/todos.md 按域分节（活跃 epic + misc 兜底，与 devlog 挂靠同规则）+ context/done.md 完成流转（记来源域）；指令 /todo /todos /tdone /todo-clean；与 dev-loop 互补（断点=唯一下一步，本表=全部积压）。（2026-09-16）
- memo-collector 迭代至 v2.1（2026-09-16）：/next 候选优先级与「断点唯一执行台」原则（todos 保持 [ ] 至完成流转）、done.md 300 行软阈值 /done 顺手归档、自动收集触发点收敛（子任务收尾/显式搁置/显式指令，严禁逐轮碎写）；v2.1 补丁——许愿池准入拦截+语义判重+脏读校验（人工 [x] 自动回收 done.md）、(block) 阻塞最高优先、/todo-groom 语义洗盘、分支结算与行级并集冲突口径；隐性资产漏斗——七类型（新增测试）/未验证假设点名/咒语→SKILL.md 进化建议（经确认执行）；agent-skill-system 同步 §1.7 信息漏斗与 2.1/2.2/3.2/3.3/4.1/4.5，backend-dev §2.6 与 frontend-dev §4 补边界推演防腐注释。
- agent-toolbox 落地（2026-09-16）：agent-skill-system 新增 §3.4 设计原则与使用说明（分层图/七原则/命令面九命令/典型流程，原环境硬约束顺延 §3.5）；v1 埋点——元工具 run-hooks 逐工具追加 usage-ledger.jsonl（ts/name/scope/exit/ms/src，append-only fail-open），list 派生 last_run 列，探针三路径验证；v1.2 check 门禁 secret 形状扫描（7 类正则逐行、命中拒收不搬家、自检金丝雀好坏样本；修复 \b 对下划线复合词 DB_PASSWORD 漏抓）；僵尸消费端待池子扩大后再做。

## copilot 记忆画像（memory-profile）

- copilot 记忆固化与画像抽取定稿（2026-09-17，决策 #11-#14）：MQ 触发 / data worker LLM 归并 / 水位 CAS 幂等；docs/copilot/memory-profile.md 评审落地——对话片段成对下发（代词消解）、topic 枚举池 + main 入库校验双保险、pinned 置顶混合召回（v2 留 pgvector 演进）、注入固定预算分段（画像/置顶/普通记忆/近3天历史，总封顶约 6.1k 字符）、近期历史排除当前会话、冷启动空注入懒积累、画像四字段（+responsePreferences）、六类记录类型表（选择/权衡/禁忌/回复偏好/习惯偏好/目标阶段）+「结论+权衡」条目约定（prompt 级口径不加列），§一分层口径修正为窗口逐段固化。

## search 域（cls 检索演进 2026-09-19）

- /api/search/cls 检索口径重构（下推 + 近窗优先）：废弃「topK×4 召回后内存过滤」过渡口径（48万语料下新数据挤不进 topK、窄时段过滤后空集）；ctime 区间/共表排除下推 SQL（(metadata->>'ctime')::bigint），EmbeddingSearchApi 门面扩 6 参（ctimeFrom/To/excludeIds），ArticleEmbeddingSearchService 手写 SQL（同 EmbeddingResultService 口径）+ 事务域 SET LOCAL hnsw.iterative_scan=relaxed_order（pgvector<0.8 一次性降级）；无 dateRange 近窗(30天, recent-window-days)优先两段式 + 全量回落(full-corpus-fallback)，dateRange 单段硬下推不回落；输出恒 ctime 倒序（相关度只决定入选）。
- 双路径路由修短查询相关性：「闻泰」类 2 字实体查询嵌入区分度差（token 重叠噪声如「纳指ETF国泰」混入近窗）→ 实体型/短查询路由 content LIKE 关键词精确路径（无阈值、ctime 倒序）；主判据 isEntityLikeQuery（纯数字代码 | 股票 name/old_name/题材 subjectName 字典包含命中），兜底无空格 ≤ short-query-max-chars(4)；pg_trgm GIN 索引（schema.sql 幂等段，planner 与 ctime 序扫描择优）；关键词 0 命中回落向量；embedding 门控收窄进向量路径（短查询不受 CF 可用性影响）。
- 分页（无限滑动）：请求 page(0起)/pageSize（topK 兼容别名），响应 hasMore（fetchDepth=depth+1 精确判定，末页不空拉）；两路径统一「前缀加深+切片」depth=(page+1)*pageSize，向量两段式同步加深保持跨页前缀性质，翻超界空页收尾；validatePage（负/超 100 → 400）；CompositeSearchService 固定 page=0。

## 公告域（检索对齐 + 向量化收口 2026-09-19）

- 公告检索对齐电报同款双路径+分页：keywordSearch（title/summary/secName/secCode LIKE + trgm GIN 幂等段）+ AnnouncementEmbeddingSearchService（announcementId 共表判别 + secCode 恒下推 + annDate 区间/近窗两段式门控）经 EmbeddingSearchApi 门面上提；实体型/短查询路由关键词 0 命中回落向量，depth=(page+1)*pageSize + hasMore 精确判定；api.md v1.6 同步。
- 公告向量化端到端收口：EmbeddingComputeWorker kind 门控放开 announcement（曾静默 skip+ack 零向量零死信）；metadata.announcementId 键义修复（内部自增 id → CNINFO 标识）+ 存量 96 行 jsonb_set 治愈；端到端验收全绿（语义命中/分页切片/关键词路由）；相关教训已入 lessons（kind 白名单同轮接线 / metadata 键以读方口径 / 42P18 四法分流）。
- 单日检索双断点修复：前端 searchSlice 丢 input.dateRange 已接线；后端 JPQL :param IS NULL 谓词 42P18 拆四个显式方法由 Service 分流，单日=start=end 闭区间契约不变。
- 部署口径：main 脱离 IDE 托管（setsid 拉起，启动器 /tmp/scs-main-launch.py，日志 /tmp/scs-main.log）；.env 真 key + JAVA_TOOL_OPTIONS JVM 代理 nonProxyHosts 排除 lavinmq 修 composite 503。

## orchestration 风险债收口（2026-09-23）

- 六项代码层收口：①事件先于挂起到达即丢 → domain_event_inbox 收件箱（fan-in 零命中落箱 + 挂起落定 replayInboxFor 同事务重放，容量 1000/类型护栏）；②cls.daily.done 批次化（ClsDailyDoneBatcher 静默窗 30s/最大延迟 300s，载荷 {article_count, article_id=末篇, ctime}）；③fan-in O(waiting) 扫描 → TaskInstanceRepository.findWaitingWithDomainEvent JSONB SQL 预过滤（jsonb_exists 规避 ? 占位符冲突），MqWaitTimeoutScanner 改 findByStatusAndWaitDeadlineBefore，CapabilityTool Top200 封顶；④保留策略 OrchestrationRetentionTask 每日 03:30（match_log 30 天/实例终态 90 天/收件箱 7 天，orchestration.retention.* 可调）；⑤存量 plan 锚混用 → PlanEmbeddingRecomputeRunner（recompute-all 门控一次性重算）；⑥公告 E2E 存量失败修复=断言/清理改 CNINFO 口径 + 孤儿向量按 CNINFO 锚直删；唤醒逻辑沉淀 MqWaitWakeService（ObjectProvider 破 Executor 构造环）。orchestration 60 测试全绿 + main 441 全绿（RABBIT_E2E 开）。

## guide 选股引导（2026-09-25，SSOT=docs/guide/design.md）

- 全量落地（M1-M6）：crawler 基包扩展（ClsDictAnchorApi.resolveEach 逐名多锚点；ClsArticleQueryApi 三两跳查询 recentArticlesByStock/subjectsByStockSince/activeStocksBySubject + 关联表 native 聚合接口投影）；search 基包 StockProfileApi 门面；guide 新域（GuideController 两端点、GuideAnalyzeService 快/慢双路径+LLM fail-open、GuideStockBriefService、GuideDtos nextStep 首位红线）；orchestration REST_SEEDS 登记 main.guide.analyze_message/stock_brief；data.sql 播种 guide:entity_extract。
- 关键定案：/api/guide/** 不挂 AuthInterceptor（D10——dispatch 工具面无会话，对齐 main.announcement.summaries 先例，设计稿「挂拦截」已废）；LLM 抽取未锚定丢弃不编造；free 链路失败 fail-open 标 llmDegraded。
- 近期验证状态：./mvnw compile 全仓通过；main 470 用例全绿；EXPLAIN 三查询全走索引（红线①）；冒烟通过（茅台档案聚合/快路径/降级/30 天窗题材两跳产出/tool_registry 自动登记）；手搓 curl MCP 握手与 SDK 序列化不匹配属既有现象（:18081 同），全链留聊天窗实测。

## 断点

- [断点] 下一步：等待散修任务
