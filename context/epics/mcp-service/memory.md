---
dev-loop: memory
format: v1
epic: mcp-service
total-merged: 0
last-merge: none
---

# mcp-service：本地自用 MCP 服务（股票指标计算 + 经典书籍知识检索）

> 独立 Maven 模块 stock-calculator-mcp（JVM 模式 :18081），Model Context Protocol 服务，
> 供 ZCode / Claude Desktop 等客户端接入。两大能力：calc（技术指标按需现算）+ kb（经典书籍 RAG）。

## 已定决策（2026-09-19 评估定案）

- 独立模块不进 main：隔离 native 构建（main 的 GraalVM AOT 一行不动）与依赖（ta4j/spring-ai-mcp-server 不泄漏）；无 MQ、无 contract 依赖、无鉴权（本地裸跑）、无 native 脚本。
- 单独数据库 stock_mcp：边界由 DB 引擎物理强制；建库后需 per-database 执行 CREATE EXTENSION vector（容器卷已初始化，initdb 脚本不重跑，手动 psql 一次）；跨库不能 SQL join（当前无此需求，字典走 Redis 已规避）。
- stock 字典走 Redis 镜像（用户定案）：main crawler 域新增镜像组件，照抄 CopilotPromptSync 范式（DB 全量镜像 → Redis，运行时只读）；key 为 stock:dict HASH（field=stock_id，value=name/oldName/isStib JSON）；mcp 启动 HGETALL 载入内存 Map（约 5000 条 1MB），代码/名称/曾用名双向解析在内存做，手动刷新端点兜底。main 现无此镜像，需新增。
- 向量化复用 bge-m3：Cloudflare Workers AI（1024 维），main/data 已有客户端（EmbeddingConfig + CfUsageFixingClient 可抄精简版）；免费额度 10000 Neurons/日，10 本书约 4300 Neurons 一天灌完；mcp 自建 kb_chunk 表（vector(1024) + HNSW cosine + content_hash + model 留档），不共用主库 vector_store 表（防与电报/公告检索互相污染）。
- 书籍 RAG 形态：整书文本入库（本地自用无版权分发风险，前提是服务不对外）；离线灌书入口放 mcp 模块（参数门控，一次性动作不 MQ 化、无状态机）；切块 500-800 字/块、50-100 字重叠、按章节边界；查询侧同模型 embedding + cosine top-k + 术语 ILIKE 兜底两路合并。
- calc 路线一（先行）：按需现算——东财/腾讯公开日线接口拉 250-500 根，ta4j 算 MA/MACD/RSI/BOLL/KDJ；只做日线（分钟线免费源不稳）；行情缓存进程内 Caffeine，不进 PG。路线二（全市场落库 → 选股/回测）独立 epic 无限期后置。
- MCP 工具粗粒度原则（4 个起步）：stock_analysis（全家桶最新值+趋势摘要，不吐全序列）/ stock_daily（原始日线）/ kb_search（向量检索+出处）/ kb_book_list（书目浏览）；LLM 客户端来回取数费上下文，宁粗勿细。
- config 复制不抽公共模块（用户定案）：RestClient/DataSource/yml 在 mcp 模块照抄小份；CLOUDFLARE_API_TOKEN 进 mcp 自己的 yml。
- 不共用 vector_store、不建术语卡（kb_search 兜住，量起来再说）。

## 分期

- M1 模块骨架 + 建库 stock_mcp；M2 字典镜像（main 小改）；M3 calc 工具；M4 kb RAG（先灌 2-3 本书调检索质量）。

## 断点

- [断点] 下一步：M1 模块骨架与建库 stock_mcp（psql 建库+vector 扩展 → 父 POM 挂载 → 模块 POM 与 yml → 启动探活）
