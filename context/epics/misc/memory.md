---
dev-loop: memory
format: v1
epic: misc
total-merged: 1
last-merge: 2026-09-13
---

# misc：散修与小改动挂靠（常驻杂项 epic）

（协议见 dev-loop skill §3「杂项挂靠」：散修/小改动统一挂靠本 epic；长成大功能则 /bind 转正，届时此处归并只留一行索引，日志不搬家）

## CI/构建

- docker-image.yml = changes 检测（dorny/paths-filter）+ build-main/build-data 双 job；contract/根POM/mvnw 变更双端重建；data 模块 Dockerfile.native（ghcr.io/<repo>-data，8080/ingest）；main build-native.sh 需父POM+contract install（CI 冷缓存必挂）。（2026-09-12）
- v2.5 worker 变体退役：删 Dockerfile.worker、build-data-worker job、build-native.sh VARIANT 分支（二进制恒三角色全开）、compose data-worker 块。（2026-09-13）

## data 拓扑/设计（pull-loop + 日历任务）

- 日历型定时任务定案：拒绝 cron→per-message TTL 种子衰变（复活已否方案、违反设计不变量 3、长 TTL 种子不可撤销）；终态 = main 看门狗 CAS 认领 + work 队列一次性直发（data 侧无续种无 delay）；pull_task_config 增 schedule_mode/cron_expression/timezone/next_expected_time 四列，LOOP 行不落 next_expected_time（健康口径 = last_renew_time 新鲜度，所有者确认）。（2026-09-13）
- 首个日历任务 task.hello.world 落地（每日 07:00 Asia/Shanghai）；watchdog 拆双节奏（watch 30min + calendarClaim 60s CAS）；main 383 / data 79 用例绿。（2026-09-13）

## 转正索引

- data 单镜像多副本改造（v2.5，2026-09-13）已转正为 epic: data-single-image——决策/踩坑/验收随迁该 epic memory.md。

## 断点

- [断点] 下一步：等待散修任务
