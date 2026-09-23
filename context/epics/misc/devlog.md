---
dev-loop: devlog
format: v1
epic: misc
total-merged: 6
last-merge: 2026-09-20
---

# misc 开发过程日志

（散修/小改动按行追加；≥5 条自动归并进 memory.md）

## 追加区
- [2026-09-20] [变更]: 新增 docs/mcp/usage.md 使用手册（启停/客户端接入/六工具速查/订阅源管理/离线加书/行情字典管理口/FAQ），README mcp 域索引同步，frontmatter 与 docs-index-lint 过检
- [2026-09-23] [变更]: orchestration 六项风险债代码层收口：①事件先于挂起到达即丢→domain_event_inbox 收件箱（fan-in 零命中落箱，挂起落定 replayInboxFor 同事务重放，容量 1000/类型护栏）；②cls.daily.done 批次化（ClsDailyDoneBatcher 静默窗 30s/最大延迟 300s 聚合，载荷升级 {article_count, article_id=末篇, ctime}）；③fan-in O(waiting 全量) 扫描→TaskInstanceRepository.findWaitingWithDomainEvent JSONB SQL 预过滤（jsonb_exists 规避 ? 占位符冲突），MqWaitTimeoutScanner 改 findByStatusAndWaitDeadlineBefore，CapabilityTool Top200 封顶+截断告警；④match_log/task_instance 保留策略落地（OrchestrationRetentionTask 每日 03:30：match_log 30 天/task_instance 终态 90 天/收件箱 7 天，orchestration.retention.* 可调）；⑤存量 plan 锚混用→PlanEmbeddingRecomputeRunner（orchestration.embedding.recompute-all=true 一次性全量按模板锚重算；本地 plan 池 0 行无对象）；⑥AnnouncementProcessMqE2ETest 存量失败修复=断言/清理改 CNINFO 标识口径（lessons 键义修复后测试未同步）+孤儿向量行按 CNINFO 锚直删；fan-in 唤醒逻辑沉淀 MqWaitWakeService（ObjectProvider 破 Executor 构造环）；orchestration 60 测试全绿 + main 441 全绿（RABBIT_E2E 开，公告 E2E 2/2 过）
