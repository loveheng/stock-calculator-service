---
memo: done
format: v2
---

# 完成列表
- [2026-09-16] toolbox check 门禁 secret 形状扫描落地：7 类正则逐行扫描、命中拒收不搬家、collect/check 双接线、自检金丝雀好坏样本、SPEC v1.2 宪法条款 (域: misc)
- [2026-09-18] (功能) task-unify 第一步：CALENDAR 概念消化——CalendarTaskClaimScheduler 独立组件 + PullLoopWatchdogTask 瘦身只留 LOOP 半区，main 384 测试全绿，用户已验收 (域: task-unify)
- [2026-09-18] (功能) task-unify 第二步：合表+executor 策略化——app_task_config 6 行并入 pull_task_config CALENDAR（ttl_ms=0）、CalendarTaskClaimScheduler 统一认领（注册表命中进程内/未命中 MQ）、schema 幂等迁移段+DROP、main 384 全绿 (域: task-unify)
- [2026-09-18] (文档) pull-loop-unification.md §8/§9 加存档注 + 新增 §10、module-split-test-plan/announcement-rag/news-search implementation 联动更新，lint 通过（memory-summary.md 缺口为存量债）(域: task-unify)
- [2026-09-18] (风险) 第二步单步面宽风险解除：测试对账 384=384−7−8+15 全绿收口；存量库 DROP 迁移属破坏性动作留人工执行 (域: task-unify)
