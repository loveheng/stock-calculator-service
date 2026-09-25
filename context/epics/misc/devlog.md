---
dev-loop: devlog
format: v1
epic: misc
total-merged: 7
last-merge: 2026-09-25
---
- [2026-09-25] [变更]: mcp-notify 提醒 JSON 解析损坏显式失败态：Repository 新增 markFailed（CAS 仅 active），Repeater 两处解析失败由静默完结改置 failed（capability 判定改三态），Watchdog/Bootstrap 解析失败逐条置 failed（Bootstrap 不再被 readTree 异常炸启动），实体状态机注释补 failed
- [2026-09-25] [验证]: ./mvnw compile -pl stock-calculator-mcp-notify -am → 通过；./mvnw test -pl stock-calculator-mcp-notify → Tests run: 9, Failures: 0, Errors: 0（新增 Repeater/Watchdog/Bootstrap 三单测）
