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
