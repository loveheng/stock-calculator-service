---
dev-loop: memory
format: v1
epic: task-unify
total-merged: 1
last-merge: 2026-09-19
---

# task-unify：任务管理统一（CALENDAR 消化 → 合表 + executor 策略化）

> 目标：把 main 侧两套定时任务体系（pull_task_config 的 CALENDAR 行 + app_task_config 的本地 job.*）统一为一套任务管理。
> 两步走，每步有独立验证点，保护测试基线——禁止「大合一步到位」。

## 两步方案（2026-09-18 评估定案，用户确认启动）

- **第一步（本 epic 当前）**：CALENDAR 概念消化——把日历认领协议（validateCalendarRows / calendarClaim / nextFire，CAS 游标语义 §8.3.2）从 PullLoopWatchdogTask（pull-loop 看门狗）提炼为 monitor 域独立组件；hello.world 接线与 MQ 拓扑不动；6 个 CALENDAR 测试场景随迁（搬家不重写断言）。语义收敛后，第二步只剩结构搬迁。
- **第二步（随任务管理模块立项时做）**：合表（app_task_config 6 行 job.* → pull_task_config CALENDAR 行，schedule_mode='CALENDAR'、ttl_ms=0）+ executor 策略化（两认领循环合一 + 分发策略：进程内 AppTaskHandler vs MQ 投递）+ 存量库幂等迁移 SQL + DROP 旧表。**不动 MQ 拓扑**（hello.world 专用队列保留，泛化共享队列属提前优化）。**→ 已实施完结（2026-09-18）：main 384 全绿，详见 devlog。**

## 评估依据（2026-09-18 依赖普查）

- app_task_config 列集（task_code/enabled/cron_expression/timezone/next_expected_time/updated_at）是 pull_task_config CALENDAR 列的**严格子集**——合表零语义发明，行迁移即 data.sql 6 行搬家。
- AppTaskScheduler 认领语义当初即对齐 §8.3.2 calendarClaim（javadoc 明示），两循环本就同构——策略化是收敛不是重写。
- 「复表污染配置快照」老顾虑已失效：快照本就只发非 CALENDAR 行（watch()/pushConfigSnapshot 均按 schedule_mode=LOOP 过滤），合表后 job.* 行天然不进快照，data 零 DB 不变量保持。
- hello.world 依赖普查：contract 2 常量（MqKey/MqQueue.TASK_HELLO_WORLD）· data MqTopologyConfig 队列+绑定 + hello/HelloWorldConsumer（~70 行自包含，仅依赖 ResultPublisher）· main 认领引擎全泛型（taskCode 只是数据：PullLoopWatchdogTask / PullLoopDispatchPort / TaskPublisher.dispatchCalendarTask）· postgres/data.sql:115 一行播种 · 测试 PullLoopWatchdogTaskTest 6 CALENDAR 场景 + HelloWorldConsumerTest 3 场景 · docs/architecture/pull-loop-unification.md §8 已实施（§8.8 实施记录）。
- 测试基线：381（用户口径；§8.8 实施时点为 main 383 / data 79）。
- 第二步实施对账（2026-09-18）：384 = 384 − 7（删 AppTaskSchedulerTest）− 8（旧 CALENDAR 场景）+ 15（重写 CalendarTaskClaimSchedulerTest：5 MQ 投递 + 5 进程内 + 5 启动校验）全绿；app-task.enabled 收窄为只压制进程内半区（E2E 契约不变，本地 false 构造用例守护）。

## 实施记录

- 第一步、第二步均已实施完结（2026-09-18）：CalendarTaskClaimScheduler 统一认领（validate 注册表 + calendarClaim 双分发）+ 合表策略化全落地，删 AppTaskScheduler*/app_task_config 三件套，schema.sql 幂等迁移段 + data.sql 播种合一 9 行，11 处 javadoc 与 application.yml 注释联动，docs 4 文档联动 + lint 通过；main 384 全绿（对账 384 = 384 − 7 − 8 + 15）。
- native 契约 DTO 缺口修复（2026-09-19，data 侧已闭环）：ContractRuntimeHints.DTO_TYPES 补登记 PullConfigPayload/PullHeartbeatPayload（message 包 27 类逐一核对仅此两个漏网），data 新增 ContractRuntimeHintsCoverageTest 包扫描守卫（message 包每个具体类必须已注册，人工约定升级为构建期断门）；data native 全量重建 201M ELF + 冒烟过 + LavinMQ 管理台直发 control.pull.config 信封实证 "pull config applied tasks=2" 全链路绿。

## 断点

- [断点] 下一步：第二步待用户验收（本地存量库手工跑 postgres/schema.sql 迁移段）；native data PullConfigPayload 缺口已修复验证，main native 需同批重建（心跳反序列化/pull config 序列化同缺口）
