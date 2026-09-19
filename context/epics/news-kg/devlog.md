---
dev-loop: devlog
format: v1
epic: news-kg
total-merged: 0
last-merge: none
---

# news-kg 开发过程日志

（散修/小改动按行追加；≥5 条自动归并进 memory.md）

## 追加区
- [2026-09-19] [变更]: 设计定稿落盘 docs/ai-pipeline/cls-news-kg.md（D1~D11 决策 + 6 表 DDL + MQ 契约 + prompt 骨架 + 分期）并完成断点一——contract 增 TASK_KG_EXTRACT / RESULT_KG_DONE、FAILED 常量与 KgExtractTask / KgExtraction / KgExtractDonePayload / KgExtractFailedPayload 四 DTO（ContractRuntimeHints 已登记，CoverageTest 守卫通过），schema.sql 增 KG 六表七索引，data.sql 播种 job.kg.extract（0 30 2 * * * Asia/Shanghai，job 行无 handler 认领器仅 warn）；验证：contract 编译 + CoverageTest 绿，DDL 与播种均经 BEGIN-ROLLBACK 试跑通过（存量库待下次 main 启动自动落）
- [2026-09-19] [变更]: 断点二完成——main kg 新域 21 文件（KgTaskStatus + ClsArticleKg/KgEvidence/KgEntity/KgRelation/KgEvent/KgEventLink 六实体、六仓库、KgProperties/KgConfig、KgExtractPublisher（最新 3 条未处理扫描发布 + 改稿重抽 + 限流熔断）、KgExtractTask handler、KgResultService（证据先行/DONE 判重/融合失败不回退）、KgFuseService（字典锚点/关系事件幂等/mention 累加）、KgHashes）+ crawler 基包三扩展（latestDigestArticles/DigestArticle、ClsDictAnchorApi 精确锚点、KgIngestApi 端口倒置）+ AppTaskHandler.TASK_KG_EXTRACT + ClsArticleMqConsumer kg 分支 + PipelineWatchTask 队列巡检与 cls_article_kg PENDING_AGE + 契约 DonePayload 补 ctime + yml kg.* 块；验证：main clean 后 423 全绿（顺手修存量残留 class：AnnouncementView 移包致旧测试 class 二进制失配）、ModulithVerify 2/2、yaml 自检过、data 编译过
- [2026-09-19] [变更]: 断点三完成（管线代码闭环）——data 侧 KgExtractWorker（Spring AI OpenAiChatModel + BeanOutputConverter 结构化抽取，prompt 注入发布时间做相对时间归一化，DONE 上行透传 hash/ctime/model；失败三分类回报 PERMANENT/TRANSIENT/RATE_LIMITED，401 直投 dead.q 不回报留 PENDING 自愈，解析失败带 rawTail）+ KgWorkerConfig（chat model 装配仿 main LlmConfig、maxRetries=0 保三分类、maxTokens 显式防 JSON 腰斩；监听工厂 prefetch=2）+ MqTopologyConfig 四 bean（task.kg.extract.q quorum+DLX、retry 环、绑定、DLX 重试绑定）；验证：KgExtractWorkerTest 7/7 + CoverageTest 绿 + data 全量 87 绿（ResultPublisherTopologyTest 单独失败属 lessons 记载的 live main 消费干扰，pid 1161728 实证，非回归）；索引归属表加 kg 行，index-lint 过
- [2026-09-19] [变更]: 历史回填（二期首项）落地——job.kg.backfill CALENDAR 行（每 30min）+ KgBackfillTask（startup 15s/DB 双触发 + 单飞守卫 + kg.backfill.enabled 总开关）+ KgExtractPublisher 抽共享发布核（daily 最新优先/backfill 最旧优先 ASC 双入口，共用限流熔断与幂等状态机）+ crawler 增 oldestDigestArticles 与 ASC 派生查询；配置 kg.backfill.*（batch-size=20/scan-multiplier=3/startup-delay=15s）；本地库已播种调度行并实测存量 1105 条（2023-08-30 起，kg 六表待重启由 sql.init 幂等补建）；验证：全仓编译过、main 423 绿、docs lint 过
- [2026-09-19] [修复]: 验收首轮即暴露 schema↔实体漂移——kg_evidence DDL 漏 created_at（实体有 @CreationTimestamp），ingestDone 全体崩；六表逐一核对仅此一处，schema.sql 与设计文档 §5 同步补列 + 本地库 ALTER 补列（已建表 IF NOT EXISTS 不补列），-Dddl-auto=validate 跑 contextLoads 全库对齐审计过；MQ 面：19 条 done 经重试环入 dead.q 停放（无需重放，回填 30min 轮次重发 PENDING 重抽自愈），1 条 worker PERMANENT 落 FAILED 待人工复查
- [2026-09-19] [变更]: kg 查询 API 落地——/api/kg/timeline（日分页卡片流，关键词三路命中：事件文本/实体名/别名，与默认浏览共用端点）+ /api/kg/entities/suggest + /api/kg/entities/{id} 共 3 端点（controller/dto/service + 三仓库原生查询 + WebConfig 鉴权 + crawler 基包 articleHeadsByIds）；顺手修 KgFuseService.parseTime 纯日期解析缺陷（LLM time 全为 yyyy-MM-dd，原仅认 OffsetDateTime/Instant 致 event_time 0 填充）并回填存量 35 条
- [2026-09-19] [变更]: cls-news-kg.md 增补 §13 查询 API 契约（三端点/日分页/三路命中/时区约定）+ §11 分期与 §12 改动面同步，文档版本 v1.1（用户确认文档联动）；自检 lint 通过，另发现 copilot 域 memory-summary.md frontmatter 存量异常已报告
- [2026-09-19] [变更]: 前端对接文档 docs/ai-pipeline/kg-api.md 落地（draft，含信封/鉴权/逐端点字段表/真实样例/交互落点/6 项待确认清单）；补第四端点 /api/kg/entities/hot 实体热榜（空态取数缺口），423 测试全绿；README 索引、index 落点列、cls-news-kg.md §11/§13/§12 同步
- [2026-09-19] [变更]: 修 timeline 原生查询运行期 42P18——temporal null 参数 IS NULL 位显式 CAST(:x AS timestamptz)（findDayAggregates/countDistinctArticle/findFilteredByArticleIds 三段同修）；/entities/hot 报 id 类型转换错为用户运行实例缺 hot 映射（旧编译产物，重启即愈）；423 测试全绿
- [2026-09-19] [变更]: 组内事件排序改逆序（用户定案最新在前）——event_time 降序、空值沉底、并列按 id 降序（原为原文阅读序）；KgQueryService.sortWithinDay 与 DTO/仓库 javadoc、cls-news-kg.md §13、kg-api.md 同步；423 测试全绿
- [2026-09-19] [变更]: native 服务器 42P18 复发（$1 keyword，AOT 绑定路径下 String null 也 untyped）——三段查询 IS NULL 位可空参数全部显式 CAST 定型（keyword text/entityId bigint/eventType text，temporal 已改），CAST 方案被服务器日志实证有效（已 CAST 的 temporal 参数未再报）；JVM 423 测试全绿 + psql 形态验证；发现 announcement 域已有同款先例注释（拆方法分流约定）与 ClsArticleRepository.searchByContentKeyword 潜在同患（Long 未实测），已报告待决策
