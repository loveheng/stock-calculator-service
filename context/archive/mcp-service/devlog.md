---
dev-loop: devlog
format: v1
epic: mcp-service
total-merged: 1
last-merge: 2026-09-20
---

# mcp-service 开发过程日志

（散修/小改动按行追加；≥5 条自动归并进 memory.md）

## 追加区
- [2026-09-20] [SSOT 修正]: 旧结论「行情缓存 Redis（quote:daily:* TTL 30min）」已废弃 ➔ 新结论「行情落库 quote_daily，DB 为日线唯一事实源」（用户需求：增量存储+全量分析；Redis 行情缓存退役，Redis 只剩字典镜像职责），design.md D9/§三/§四/§5.4/§七 与 implementation.md M5 同步
- [2026-09-20] [变更]: M5 行情落库落地——quote_daily 表 + QuoteDailyRepository（findMaxTradeDate/findRecentN/findRange）+ QuoteSyncService（全窗口/增量 last-10d 判定 + JdbcTemplate ON CONFLICT 批量 upsert + 读库）+ EastmoneyDailyClient 纯化 fetchWindow + 工具改读库 + 管理口 resync/bars/status；单测 21/21；E2E：首调全量 65 根落库、二刷增量 9 根、analysis 356 根读库指标值与改前一致、Redis 行情键零残留
- [2026-09-20] [变更]: quote_daily 字段扩展至东财全 11 字段（用户需求补成交量类数据——volume 原本已存，新增 amount/amplitude/pct_chg/chg/turnover）——DailyBar/实体/DDL（存量库 ALTER IF NOT EXISTS 补列）/fields2/解析（缺位容错 0）/upsert/工具与管理端点输出同步；stock_daily 输出加 amount/pctChg/turnover；单测 21/21
- [2026-09-20] [变更]: resync 大窗口丢行修复——JPA 派生 deleteByStockId 的 DELETE 延迟到 flush，排在 JdbcTemplate upsert 之后把同键新行连带删除（幸存行=旧库外行的互补模式定位，PG 语句日志实证）；删除改 JDBC 立即执行，sh600519 resync 684/684 无断段落库（2023-11-27→2026-09-18）；教训入 lessons
- [2026-09-20] [变更]: M6 支撑压力工具落地——indicator/SupportResistanceService 三法合一（昨根枢轴点公式平盘同价去重、摆动高低点严格极值+1.5% 聚类成带计触及、近似 Volume Profile 30 桶摊派取前 3 HVN）+ tool/StockLevelsTool（stock_levels，现价上下各 ≤5 档位带带依据）；D10 决策入 design.md §九/§十；单测 25/25（手算对照 4 条）；MCP 验收 6 工具注册、贵州茅台真算通过（swing 带 5 次触及/近 60 日 25 根触及、旧低点带转压力角色互换正确）
