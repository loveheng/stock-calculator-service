---
dev-loop: memory
format: v1
epic: orchestration-impl
total-merged: 1
last-merge: 2026-09-23
---

## 结论

- [2026-09-23] 范围：orchestration 五项待办 P1-P4 落地；P5（Executor 并行化）明确不做。规划全文 context/plans/orchestration-impl-plan.md
- [2026-09-23] P1 复用链路：slots 落库修复；use_count 改 done 终态补记（TaskTerminalHandler 统一四处 run 完成点）；match_log 表（append-only，六种回退归因）；draft 孪生向量去重（更新原条目重过冒烟）；SmokeGateService 自动冒烟闭环（params.purpose=smoke+dry_run，冒烟只验结构不触外部系统，D10 第一环接通——此前全仓无写 candidate 路径）
- [2026-09-23] P2：HITL 拒绝联动 plan 置 rejected 终态（区别于 deprecated=过时）+ reviewer_note 留痕 + rejected 向量负样本前置规避复用
- [2026-09-23] P3 领域事件化：contract 增 event.* 契约与 stockcalc.events 交换机；main 两源（cls 日报 done / announcement done.<secCode>）经 common.DomainEventPublisher afterCommit 发布；DomainEventFaninListener fan-in 唤醒（事件类型 + filter 标量归一匹配：数值形态 + 股票市场后缀 startsWith 双向 + $.params 引用）；旧 trace_id 回调保留兼容；编排器除 MqWaitTimeoutScanner 外零轮询
- [2026-09-23] P4 三件套：intent_template 模板向量锚（参数不入锚）；feasible/gap 能力判定（no 显式报受限，gap 随 create_task 回传）；list_capabilities 能力卡片工具（verified 池聚合）；Planner prompt 含节点类型词表（mq_wait 事件白名单 announcement.done/cls.daily.done、switch、foreach；sleep 不教）
- [2026-09-23] 可靠性：InstanceRecoveryRunner 重启恢复 running 孤儿（updatedAt 10 分钟阈值防 MQ redelivery 竞争）；waiting 交唤醒监听器重绑 + 超时扫描兜底
- [2026-09-23] 验证：OrchestrationReuseChainE2ETest 五场景全链（SMOKE_E2E 门控，真 PG+LavinMQ，LLM/embedding 桩），orchestration 60 测试全绿；main E2E 死信断言改基线增量（4184 条历史死信不误报）；docs 六处联动 + lint 过
- [2026-09-23] 遗留：AnnouncementProcessMqE2ETest 向量行存量失败（HEAD 同挂，待专项，见 todos）；低优先债清单见 todos orchestration 节

## 断点
- [断点] 已归档（2026-09-23 @done 收尾，epic 完结）——后续事项见 todos 与 plans/orchestration-impl-plan.md
