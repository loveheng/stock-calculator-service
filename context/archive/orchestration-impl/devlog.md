---
dev-loop: devlog
format: v1
epic: orchestration-impl
total-merged: 0
last-merge: none
---
- [2026-09-23] [变更]: P1 复用链路地基 5 项落地——Planner.fullPlan 接收 normalizeIntent slots（param_schema 不再恒空）、use_count 移 TaskRunner 终态补记、match_log 表+命中/回退写入、draft 孪生向量近邻去重、SmokeGateService 冒烟闭环（purpose=smoke+dry_run+Executor 冒烟模式只验结构）
- [2026-09-23] [变更]: P2 HITL 拒绝状态机——plan 置 rejected 终态+reviewer_note 留痕，matchVerified 前置 rejected 负样本近邻规避
- [2026-09-23] [变更]: P3 领域事件化——contract 增 event.* 契约与 stockcalc.events 交换机，main 两源（cls 日报 done / announcement done.<secCode>）经 common.DomainEventPublisher afterCommit 发布，orchestration 新增 DomainEventFaninListener（fan-in 队列，event 类型+filter 精确匹配/$.params 引用解析唤醒 mq_wait）
- [2026-09-23] [变更]: P4 Planner 三件套——normalizeIntent 增产 intent_template 并切向量锚、规划 prompt 输出 feasible/gap（no 显式报能力受限）、CapabilityTool.list_capabilities 以 verified 池聚合能力卡片挂 MCP 工具面
- [2026-09-23] [变更]: 适配测试——PlannerFillParamsTest/ExecutorBranchTest 新签名 + foreach stub 4 参 invoke（上一会话 dry-run 遗留测试债）；Modulith 红线修复 DomainEventPublisher 上移 common 基包；orchestration 55 / main 433 测试全绿
- [2026-09-23] [变更]: 新增 OrchestrationReuseChainE2ETest（P1-P4 四场景联动 E2E，SMOKE_E2E 门控，真 PG+LavinMQ，LLM/embedding 桩）——①规划落库(slots/template/match_log)→自动冒烟→candidate ②孪生去重 ③verified 复用→MQ 真跑→use_count 补记 ④拒绝联动→rejected_near 规避→领域事件 filter 匹配唤醒（错事件不误唤醒）；SMOKE_E2E 全量 59 测试全绿
- [2026-09-23] [风险]: main 侧 RABBIT_E2E 套件 7 失败为共享 broker 死信堆积（dead.q 4184 条历史脏数据，「死信=0」断言过脆），非本次改动回归——待清理 dead.q 或断言改基线相对值
- [2026-09-23] [变更]: 查漏二批①②④⑤——HitlReviewService approve 补 use_count；终态处理抽 TaskTerminalHandler（TaskRunnerListener/InstanceRecoveryRunner 共用）；新增 InstanceRecoveryRunner 重启恢复（running 孤儿断点续跑+终态统一处理）；TaskTool 回传 feasible/gap；DomainEventFaninListener filter 标量归一（BigDecimal 尾零 + 市场后缀 startsWith 双向匹配）防 ParamGuardrail 后缀差静默不唤醒；③（prompt 节点词表）留 todos 单独排
- [2026-09-23] [变更]: 查漏二批③——fullPlan prompt 补节点类型词表（mq_wait 事件等待含可等事件白名单与 filter $.params 引用语义、switch/foreach 完整 schema，mq_send/hitl_wait 慎用标注），加 MqKey 契约三方同步纪律注释；E2E 场景5 验证 Planner 产出事件等待 DAG 端到端（冒烟 mock 挂起→candidate，真实执行挂起→事件唤醒→done）；orchestration 60 测试全绿
- [2026-09-23] [变更]: 断点清偿——文档联动 agent-orchestration.md 六处（plan 表/Planner 八条/Executor 领域事件化+冒烟闸门/实施记录）+ docs lint 通过；main E2E 死信断言改基线增量（5 类），RABBIT_E2E 从 7 挂降至 1 挂；git stash HEAD 对照证实剩 1 失败为存量问题（vector row），另立 todos
