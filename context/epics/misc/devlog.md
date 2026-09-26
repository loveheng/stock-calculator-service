---
dev-loop: devlog
format: v1
epic: misc
total-merged: 7
last-merge: 2026-09-25
---
- [2026-09-25] [变更]: mcp-notify 提醒 JSON 解析损坏显式失败态：Repository 新增 markFailed（CAS 仅 active），Repeater 两处解析失败由静默完结改置 failed（capability 判定改三态），Watchdog/Bootstrap 解析失败逐条置 failed（Bootstrap 不再被 readTree 异常炸启动），实体状态机注释补 failed
- [2026-09-25] [验证]: ./mvnw compile -pl stock-calculator-mcp-notify -am → 通过；./mvnw test -pl stock-calculator-mcp-notify → Tests run: 9, Failures: 0, Errors: 0（新增 Repeater/Watchdog/Bootstrap 三单测）
- [2026-09-26] [变更]: schema.sql 迁入 main resources 收口为 SSOT（pom 删 ../postgres 拷贝块）；application-postgres.yml 非法 mode: validate 修回 always；data.sql 退化为纯记录（不随包不执行，播种改 psql 手工/CI 预灌）；docker-image.yml 四 job 的 psql schema 路径改指新位置；README/service-index/docs(12 处)/代码注释/free-canvas 记忆全仓引用联动
- [2026-09-26] [验证]: ./mvnw -q compile -pl stock-calculator-main -am → 通过；target/classes 含 schema.sql 且无 data.sql；docs-index-lint → 唯一 FAIL 为存量问题 docs/toolbox/run.md 缺 README 条目（与本轮无关，未动）
- [2026-09-26] [变更]: pull_task_config 基础设施种子（12 行/4 段 INSERT）自 postgres/data.sql 并入 main resources schema.sql 尾部播种区，随建表自动执行；data.sql 收敛为仅 copilot prompt 模版纯记录；application.yml / application-postgres.yml / pom / CI 注释、README、service-index、pull-loop-unification.md §9.4 runbook 同轮联动
- [2026-09-26] [验证]: ./mvnw -q compile -pl stock-calculator-main -am → 通过；target/classes 含 schema.sql（含播种区）无 data.sql；schema.sql 内 pull_task_config INSERT 计数 5 = 既有 app_task_config 合表迁移段 1 + 新搬入 4，无重复；未对库实跑（middleware 容器未启动），SQL 为 data.sql 原文逐字搬移且全部幂等
- [2026-09-26] [变更]: copilot prompt 模版种子（8 段 INSERT 原文搬移，未去重 data.sql 既有重复块）自 postgres/data.sql 并入 main resources schema.sql 播种区，postgres/data.sql 删除收口（表结构+种子单文件 SSOT）；CI 四 job 删 data.sql 行、main 注释重写；README 免手工 SQL、双 yml、pom、service-index、docs 6 处活引用联动（带「原 data.sql 已并入」溯源注记）
- [2026-09-26] [验证]: ./mvnw -q compile → 通过；target/classes 仅 schema.sql；schema.sql 内 copilot_prompt_template INSERT=8、pull_task_config INSERT=5、播种区横幅唯一、无「纯记录/psql 手工」残留；未对库实跑（middleware 容器未启动），SQL 为原文搬移且全幂等
