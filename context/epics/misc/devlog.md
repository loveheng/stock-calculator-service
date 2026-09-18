---
dev-loop: devlog
format: v1
epic: misc
total-merged: 4
last-merge: 2026-09-17
---

# misc 开发过程日志

（散修/小改动按行追加；≥5 条自动归并进 memory.md）

## 追加区
- [2026-09-18] [变更]: main 侧 6 个 cron 型定时任务调度 DB 化——新增 app_task_config 表 + AppTaskHandler/AppTaskScheduler（认领语义 mirror 看门狗 CALENDAR 三语句），yml 删全部调度 cron/enabled 键（announcement/search.backfill/embedding/pipeline.watch），6 任务类 implements AppTaskHandler，AnnouncementProcessPublisher 删 enabled 门控，E2E 压制切 app-task.enabled=false；main 381 测试 0 失败（含 ModulithVerifyTest）；文档联动 pull-loop-unification §9 / announcement-rag §7 / news-search implementation / module-split-test-plan。
