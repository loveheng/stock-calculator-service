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
