---
dev-loop: devlog
format: v1
epic: misc
total-merged: 5
last-merge: 2026-09-19
---

# misc 开发过程日志

（散修/小改动按行追加；≥5 条自动归并进 memory.md）

## 追加区
- [2026-09-19] [变更]: 公告检索对齐电报同款双路径+分页：AnnouncementQueryApi/Repository 新增 keywordSearch（title/summary/secName/secCode LIKE + DONE/摘要非空 SQL 内，公告 trgm GIN 索引 schema.sql 幂等段）；新增 AnnouncementEmbeddingSearchService（announcementId 键判别共表 + secCode 恒下推 + annDate 区间/近窗两段式门控下推 + iterative_scan 同款兜底）并经 EmbeddingSearchApi.announcementSimilaritySearch 门面上提；AnnouncementSearchService 重构为实体型/短查询路由关键词 0 命中回落向量 + depth=(page+1)*pageSize 分页（hasMore 精确判定），过渡期 kind-filter-enabled=false 保持 ×4 放大 + 回查内存过滤（B8 前存量行缺 annDate 实证：本地库公告向量 0 行）；DTO page/pageSize/hasMore + Controller/Composite 适配；api.md v1.6（§1/§2/§3 契约）+ implementation.md §2 联动；新增 AnnouncementSearchServiceTest(13) + AnnouncementEmbeddingSearchServiceTest(6)，main 431 全绿（9 E2E 门控跳过）
- [2026-09-19] [修复]: 公告向量化端到端联调发现 data 计算端缺口：EmbeddingComputeWorker kind 门控仅放行 cls_article（阶段 4 遗留「随阶段 4 接入」未接线），kind=announcement 任务被业务性 skip+ack——发布/回程/落账三段均正常却零向量零死信；放开门控（公告文本随任务下发，计算流程与 cls 无分支差异）+ 补 announcementKindComputed 单测（9/9 绿）；发布端链路同时实证：job.announcement.process 拨游标即触发，蒸馏在跑（接地校验定向重试/LLM 空响应重试语义正常），嵌入任务待 data 重启后由下轮重发（D6 未终态每轮重发）
- [2026-09-19] [修复]: 公告向量化端到端联调二次排障——PG 语句日志（ALTER SYSTEM log_statement 临时开启+池连接重建+已还原）实证向量 SQL 正常下发（1024 维向量/阈值 0.3/topK 44）但回查零命中：写入侧 AnnouncementEmbeddingMqService.applyEmbeddingResult 把内部自增 id 写进 metadata.announcementId，而读取方（回查 findByAnnouncementIdIn/resultId 契约）按 CNINFO 标识查询——存量 bug，此前向量行数恒 0 故从未暴露；修复写入键值（getAnnouncementId）+ 存量 96 行 jsonb_set 治愈 + 测试断言更新（22/22 绿）；终端接管 main/data 运行（.env 配置），端到端验收全绿：向量路径语义命中（减持质押→解除质押公告）、自相似命中、分页切片不重叠、越界空页收尾、关键词实体路由照常
- [2026-09-19] [变更]: main 带代理重启修复 composite 摘要 503——.env 新真 key（groq 渠道即成功）经 JAVA_TOOL_OPTIONS 注入 JVM 代理 192.168.1.40:2080 + nonProxyHosts 排除 lavinmq 防内部 HTTP 被劫持；main 已脱离 IDE 托管（setsid 拉起，日志 /tmp/scs-main.log，启动器 /tmp/scs-main-launch.py）
- [2026-09-19] [变更]: 单日检索双断点修复——前端 searchSlice 构造请求时丢弃 input.dateRange（UI 选日期静默无效）已接线并入 req；后端公告关键词检索带日期 500（JPQL :param IS NULL 谓词非 null LocalDate 绑定触发 PG 42P18）拆为四个显式方法由 Service 分流；单日=start=end 闭区间契约不变，公告/电报/composite 全链路实测通过（main 423 测试绿，前端 781 测试绿）
