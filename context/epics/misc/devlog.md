---
dev-loop: devlog
format: v1
epic: misc
total-merged: 0
last-merge: none
---

# misc 开发过程日志

（散修/小改动按行追加；≥5 条自动归并进 memory.md）

- [2026-09-12] [变更]: dev-loop skill 升级 V3.2（新增 misc 常驻杂项挂靠协议：默认绑定、转正出口、audit/done 护栏），本项目同步落盘 misc 骨架
- [2026-09-12] [变更]: CI 按模块条件构建：docker-image.yml 重构为 changes 检测 + build-main/build-data 双 job（dorny/paths-filter 路径过滤，contract/根POM/mvnw 变更双端重建，tag/手动强制全量），新增 data 模块 Dockerfile.native（镜像名 ghcr.io/<repo>-data，8080/ingest），修复 main build-native.sh 拆分后缺失的父POM+contract install（CI 冷缓存会挂）
- [2026-09-12] [变更]: dev-loop skill 升级 V3.3（补 3 处口径：CURRENT 缺失/失配处置、归并与 /status 计数对象含 misc、/file 裸调用）
- [2026-09-13] [结论]: 日历型定时任务（cron）评估——拒绝 paste 中的「cron 衰变为 per-message TTL 种子」纯 MQ 方案（等价复活已否决的方案甲、违反设计不变量 3、7 天种子不可撤销/队头阻塞），采纳其自修正后的终态：扩展 main 看门狗（pull_task_config 增 schedule_mode/cron_expression/next_expected_time，周期降 60s，CAS 认领）+ 日历任务一次性投递 work 队列（data 侧无续种、无 delay 队列、无 bootstrap 种子）
- [2026-09-13] [变更]: docs/pull-loop-unification-design.md 增补 §8 日历型定时任务扩展设计（CALENDAR 模式，设计定稿待实施）——L8…L14：看门狗拆双节奏（watch() 30min 不动 + calendarClaim() 60s）、pull_task_config 增 schedule_mode/cron_expression/timezone/next_expected_time 四列、CAS 认领 + 直发一次性任务（无 delay 队列无种子无续种）、skip-missed 补跑、Spring CronExpression 方言；被否项存档（cron→TTL 衰变种子等 4 项）
- [2026-09-13] [变更]: 首个日历任务 task.hello.world 落地（每日 07:00 Asia/Shanghai 打印 Hello World）——§8 全链路实施：pull_task_config 四列（含存量幂等 ALTER）、CALENDAR 播种、watchdog 拆双节奏（calendarClaim 60s CAS 认领 + @PostConstruct cron fail-fast）、TaskPublisher 裸消息直发、data HelloWorldConsumer 一次性消费；main 383 / data 79 绿；设计文档 §8 状态更新 + §8.8 实施记录
- [2026-09-13] [变更]: 设计文档 §8.1 分域表补"下次触发时间"行与库内记账边界注——LOOP 行不落 next_expected_time（周期自适应顺延无确定值，健康口径 = last_renew_time 新鲜度），CALENDAR 行游标落库供 CAS 认领；项目所有者确认"财联社类周期任务不需要知道下次执行时间"
