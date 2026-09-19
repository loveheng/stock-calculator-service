---
status: active
updated: 2026-09-19
---

# MCP 服务实现规划（stock-calculator-mcp）

> 设计依据 docs/mcp/design.md；本文只落里程碑、文件清单与验证口径。四个里程碑可独立交付，
> M3 结束即有可用的 calc 工具，M4 结束全量。

## 一、里程碑总览

| 里程碑 | 内容 | 交付物 |
|---|---|---|
| M1 | 模块骨架 + 建库 stock_mcp | mcp 模块可启动 :18081，kb 空 DDL 就位 |
| M2 | 字典镜像 | main crawler 域镜像组件 + mcp 内存字典 |
| M3 | calc 工具 | stock_analysis / stock_daily 两个 MCP 工具可用 |
| M4 | kb RAG | 灌书入口 + kb_search / kb_book_list，先灌 2-3 本书验证 |

## 二、M1 模块骨架与建库

1. 一次性初始化（psql 手动，留档到本节）：
   - CREATE DATABASE stock_mcp;
   - 在 stock_mcp 内执行 CREATE EXTENSION IF NOT EXISTS vector;
2. 父 POM：modules 增加 stock-calculator-mcp；dependencyManagement 沿用根 BOM（Spring AI 2.0.1 已在管）。
3. 模块 POM：按 design.md §四 依赖表；包结构 tool/kb/quote/indicator 空壳。
4. application.yml（mcp 自己的，config 复制原则）：
   - server.port=18081
   - spring.datasource.url 指向 stock_mcp 库（口令走 .env 的 POSTGRES_PASS，不入仓）
   - spring.sql.init 执行 kb 两表 DDL（IF NOT EXISTS 幂等）
   - spring.ai.openai 指向现有兼容端点；CLOUDFLARE_API_TOKEN 由环境注入
   - spring.ai.mcp.server 配置（服务名/版本/工具注册）
5. 骨架验证：全仓编译过 + mcp 模块启动日志出现 MCP 端点 + curl 端点探活。

## 三、M2 字典镜像（main 小改）

1. crawler 域新增 StockDictRedisSync（照抄 CopilotPromptSync：启动全量 HSET + 变更点增量刷）。
2. 变更刷挂点：StockService 字典更新路径完成后增量写（field=stock_id）。
3. mcp 侧：StockDictMemoryService（启动 HGETALL 载入 + 刷新端点 + 名称模糊解析 contains）。
4. 验证：redis-cli HLEN stock:dict 与 stock 表行数一致；mcp 内存解析「茅台」命中 600519。

## 四、M3 calc 工具

1. quote/：EastmoneyDailyClient（RestClient 拉日线 250-500 根）+ TencentDailyClient 备用实现位；
   Caffeine 缓存（key=stockId+days，TTL 到收盘后失效粒度即可）。
2. indicator/：ta4j 封装——MaSeries/MacdSeries/RsiSeries/BollSeries/KdjSeries，
   输入 BarSeries 输出最新值 + 近 N 日趋势摘要（升/降/金叉死叉标记）。
3. tool/：StockAnalysisTool / StockDailyTool（@Tool 注解，description 按 design.md §九口径写）。
4. 验证：
   - 单测：指标数值与手工计算样例对照（MA/MACD 各一组固定数据断言）；
   - 客户端实测：ZCode/Claude 里配 MCP 端点后自然语言触发「算一下 600519 的指标」。

## 五、M4 kb RAG

1. kb/ 实体与仓库：KbBook / KbChunk（vector 列用 pgvector 类型映射，参考现有 PgVectorStore 用法）；
   KbChunkRepository 原生 cosine 查询（可空参数显式 CAST，见 lessons 42P18 纪律）。
2. ingest/：BookIngestRunner（profile/参数门控 CommandLineRunner）——
   抽文本 → 章节切块 → 批量 embedding（批次大小照抄 EmbeddingComputeWorker）→ 写库；重灌即 truncate 重载。
3. tool/：KbSearchTool（ILIKE 精确 + 向量两路合并，join kb_book 拼出处）/ KbBookListTool。
4. 启动校验：embedding 模型与 kb_book.embedding_model / 1024 维一致性。
5. 验证：先灌 2-3 本最常翻的书 → 「安全边际是什么意思」返回正确出处 → 术语 ILIKE 命中 →
   「读过哪些书」走 kb_book_list。

## 六、验证命令

```sh
# 全仓编译（含新模块）
./mvnw compile

# mcp 模块单测
./mvnw test -pl stock-calculator-mcp

# main 模块单测（M2 字典镜像后回归；无 DB 环境排除两个 @SpringBootTest）
./mvnw test -pl stock-calculator-main -am '-Dtest=!StockCalculatorApplicationTests,!SyncBackupL1IntegrationTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-DfailIfNoTests=false'
```

- @SpringBootTest / MCP 客户端实测依赖本地中间件容器存活（compose + .env 口令，见 workflow skill）。
- mcp 模块不涉及 native 构建与 E2E MQ 门控。

## 七、文档联动

- M3/M4 工具定稿后：本域新增 docs/mcp/api.md 固化工具契约（design.md §九 为活口径，api.md 为冻结快照）。
- 灌书管线若调参（切块/批次）：回写 design.md §8.2。
- main 侧 StockDictRedisSync 属 crawler 域核心流程：完成后按 docs 规范 §二 提示核对 crawler 相关文档。
