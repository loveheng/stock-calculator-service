---
dev-loop: devlog
format: v1
epic: task-unify
total-merged: 0
last-merge: none
---

# task-unify 开发过程日志

（散修/小改动按行追加；≥5 条自动归并进 memory.md）

## 追加区
- [2026-09-18] [变更]: 第一步完成——CALENDAR 认领协议从 PullLoopWatchdogTask 提炼为独立组件 CalendarTaskClaimScheduler（validateCalendarRows/calendarClaim/nextFire 原样随迁，§8.3.2 语义零改动），看门狗只留 LOOP 判活补种；5 个认领场景随迁新测试类 + 新增 3 个启动校验用例；AppTaskScheduler 两处 javadoc 指针改指新类；main 测试 384 绿（381 基线 −5 迁出 +8 = 384 对账平）；docs §8 现行描述联动随第二步重构（已挂待办）
- [2026-09-18] [变更]: 第二步一次性迁移完成——CalendarTaskClaimScheduler 统一认领（validate 注册表 + calendarClaim 双分发：命中 handler 进程内/未命中 MQ）、删 AppTaskScheduler/AppTaskConfigEntity/AppTaskConfigRepository/AppTaskSchedulerTest、schema.sql 幂等迁移段+DROP 旧表、data.sql 播种合一 INSERT 9 行、11 Java javadoc + application.yml 3 注释联动、CalendarTaskClaimSchedulerTest 重写 15 用例；main 384 全绿（0 失败 9 skipped，对账 384=384−7−8+15）；docs 4 文档联动（§10 新增）+ lint
- [2026-09-19] [变更]: native data 运行期反序列化崩溃修复——ContractRuntimeHints.DTO_TYPES 补登记 PullConfigPayload/PullHeartbeatPayload（pull-loop 新增 DTO 漏登记，message 包 27 类逐一核对仅此两个漏网，嵌套 TaskConfig 随宿主自动覆盖）；data 新增包覆盖守卫测试 ContractRuntimeHintsCoverageTest（message 包每个具体类必须已注册）；data native 全量重建 201M ELF + 冒烟过 + LavinMQ 管理台直发 control.pull.config 信封实证日志 "pull config applied tasks=2" 全链路绿；main native 同缺口（心跳反序列化/pull config 序列化）待同批重建
