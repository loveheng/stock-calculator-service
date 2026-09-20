---
dev-loop: memory
format: v1
epic: mcp-service
total-merged: 1
last-merge: 2026-09-20
---

# mcp-service（已完结，2026-09-20 @done 归档）

- stock-calculator-mcp 独立模块 :18081（JVM/SSE），独立库 stock_mcp，与 main 唯一接触面 = Redis stock:dict 字典镜像（5643 条，内存三路解析：代码/名称/曾用名）。
- 工具：6 个注册 = stock_analysis / stock_daily / stock_levels / kb_search / kb_book_list + ping 探活；管理口 resync/bars/status、dict refresh/resolve。
- calc：东财日线 11 字段按需落库 quote_daily（qfq，增量 last_date-10d upsert，resync 修除权远端漂移；DB 唯一事实源 D9）→ ta4j 0.17 现算 MA/MACD/RSI/BOLL/KDJ；支撑压力=位带三法合一（D10）。
- kb：EPUB 灌书（600/80 切块）+ bge-m3（CF 原生 REST）→ kb_book/kb_chunk（vector(1024) HNSW cosine + content_hash + model 留档），cosine+ILIKE 双路合并带出处；三本书 509 块。
- 纪律：MCP 工具粗粒度返回（宁粗勿细）；Redis 单一缓存体系（行情缓存退役）；lessons 4 条（MCP initialize 时序 / ta4j 字节码版本 / Jackson3 isStib 键名 / JPA 删除与 JDBC upsert 同事务混用）。
- 验证收口：单测 25/25，工具端到端通过；文档 docs/mcp/design.md + implementation.md（D1-D10）。

## 断点

- [断点] 本 epic 已完结归档（2026-09-20 用户 @done）；后继：博主观点库 + 人格提炼新 epic（存量 txt + 增量 RSS）；全市场日线批量灌入（路线二）无限期后置。

## 进度补遗（归档后回补 2026-09-20，另一会话归档时快照偏旧）

- M5 扩展：quote_daily 补全东财全 11 字段（新增 amount/amplitude/pct_chg/chg/turnover；volume 原本就有），存量库 ALTER IF NOT EXISTS 补列 + resync 回填；stock_daily 输出加 amount/pctChg/turnover。
- M5 修复：resync 大窗口丢行——JPA 派生 deleteBy 排队 flush 的 DELETE 吃掉 JDBC upsert 新行（幸存行互补模式定位，教训在 lessons），删除改 JDBC 立即执行；sh600519 resync 684/684 无断段落库（2023-11-27→2026-09-18）。
- M6：支撑压力工具 stock_levels 落地（D10）——SupportResistanceService 三法合一（枢轴公式/摆动严格极值+1.5% 聚类/近似 Volume Profile 30 桶取前 3 HVN），现价上下各 ≤5 档位带带依据；单测 25/25；茅台真算验收（旧低点带转压力角色互换正确）。工具共 6 个。
- 运维：mcp 改 setsid nohup 脱离会话常驻（日志 /tmp/mcp-run.log）——会话后台任务跑 spring-boot:run 会被 SIGTERM 回收（08:27 优雅停机实证）；当前实例存活，茅台 684 根在库。
