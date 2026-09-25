---
status: active
updated: 2026-09-24
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

1. quote/：TencentDailyClient（RestClient 拉腾讯 fqkline 日线，2026-09-24 起为主实现，见 §十）
   + EastmoneyDailyClient 备用实现位（预留）；
   Redis 行情缓存（key=quote:daily:{stockId}|{days}，TTL 30min；2026-09-20 由 Caffeine 改定，见 design.md §5.4）。
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

## 七、M5 行情落库（2026-09-20 追加）

- quote_daily 表（sql.init 自动建）；QuoteSyncService：全窗口/增量窗口（last_date-10d）判定 + JdbcTemplate
  ON CONFLICT 批量 upsert + 读库；EastmoneyDailyClient 纯化为 fetchWindow（无缓存；
  2026-09-24 起拉取改由 TencentDailyClient 承担，见 §十）。
- 工具 stock_analysis/stock_daily 改读库；管理口 POST /admin/quote/resync（漂移修复）、
  GET /admin/quote/bars?stockId&from&to（全量分析读取）、GET /admin/quote/status。
- Redis 行情缓存（quote:daily:*，当日寿命半日）由落库取代退役。

## 八、M6 支撑压力工具（2026-09-20 追加）

- indicator/SupportResistanceService：三法合一——昨根枢轴点公式（P/R1/S1/R2/S2，平盘日同价去重）、
  摆动高低点严格极值检测（左右各 3 根确认，末段未确认不计）+ 1.5% 阈值聚类成带（触及计数）、
  近似 Volume Profile（30 桶摊派 volume，HVN=均值 1.8 倍以上相邻合并，取前 3 带）。
- tool/StockLevelsTool（stock_levels）：现价上方为压力由近及远、下方为支撑由近及远各 ≤5 档；
  输出位带（low-high）+ 类型 + 触及次数/成交量占比 + 距现价 %。手算对照单测 4 条。

## 九、文档联动

- M3/M4 工具定稿后：本域新增 docs/mcp/api.md 固化工具契约（design.md §九 为活口径，api.md 为冻结快照）。
- 灌书管线若调参（切块/批次）：回写 design.md §8.2。
- main 侧 StockDictRedisSync 属 crawler 域核心流程：完成后按 docs 规范 §二 提示核对 crawler 相关文档。

## 十、数据源切腾讯（2026-09-24 追加）

- **动机**：前端图表取腾讯数据，后端同源消除口径差（复权/价格/成交量一致）。
- **实现**：`TencentDailyClient` 对接 `web.ifzq.gtimg.cn/appstock/app/fqkline/get`（免鉴权公开接口，
  `param={code},day,{start},{end},{count},{fq}`，fq=qfq 前复权/空=不复权，返回 `qfqday`/`day`
  每行 [日期,开,收,高,低,量(手)]）；`EastmoneyDailyClient` 删除（东财降为 `DailyQuoteClient` 备用实现位）。
- **单请求上限**：实测约 800 根（6000 直接 param error）——按 640 根/页向更早分页回溯
  （end=上一页最早日-1），覆盖全量/重灌最大回看窗（forceResync 上限 2000 交易日）。
- **字段派生**：腾讯行无额/振幅/涨跌幅/换手——chg/pctChg/amplitude 由前一根收盘派生
  （窗口前冗余 15 日历日取前收，beg 首根因此有基准；新上市首根派生值 0），
  amount/turnover 恒 0；quote_daily 11 列结构与 upsert 管线不变。
- **配置**：频控配置键 `quote.eastmoney.*` → `quote.tencent.*`（默认值不变，yml 未显式配置无破坏）。
- **测试**：TencentDailyClientTest（代码归一/qfq 派生/raw/分页/空数据容错）；
  QuoteSyncServiceTest mock 迁移至 TencentDailyClient。

## 十、M7 博主观点库 M1（2026-09-20 追加，epic: mcp-blogger-kb）

- 订阅源注册表 kb_source（name 唯一 / source_type text|rss / location / status active|removed）+
  /admin/source REST 口：POST 注册即灌入、DELETE 移除（停更保数据，检索侧过滤 removed 源）、
  GET 清单（含块数）。RSS 拉取属 M1b，注册入口先行拒绝避免半注册源。
- 微博备份解析 KbWeiboBackupParser：头块跳过、一条微博=一个观点单元 chunk、published_at 提取、
  纯媒体条目（分享图片/视频）与「转发微博」标记行剔除；非微博格式 txt 回落 600/80 切块。
- 博主伪书：kb_book category=blogger + source_id 关联（kb_book/kb_chunk 补列 ALTER IF NOT EXISTS）；
  KbIngestService.ingestSource 与灌书共用批量向量化写入；灌入幂等 = 按书 truncate 重载。
- E2E 实证：注册「麻辣新鲜」（微博备份 59 条）→ 55 块入库全部带 published_at（09-06→09-20）→
  删除后块保留（停更保数据）→ 重注册重灌 55/55 → MCP kb_search 真调命中「麻辣新鲜/微博时间」
  出处，与书籍段落同榜返回。旧实例占 18081 需先杀再起新构建。
- 单测 33/33（新增解析器/灌入编排/源服务 8 条）。
- M1b（同日追加，RSS 增量）：KbRssFeedParser（JDK DOM 零依赖；RSS 2.0/Atom；标题型 feed 的
  description 复读标题自动去重，块=标题+链接；RFC-822/ISO-8601 时间双容错折算系统时区）+
  KbRssPoller（默认 6h，kb.rss.* 门控，逐源 fail-open）+ POST /admin/source/{name}/refresh 手动刷新；
  ingestSourceRss 按 content_hash 增量只补新条目（chunk_index 续排，chapter_path=条目标题）。
  E2E：注册「政策法规」（gov.cn 标题型 feed）首拉 11/11、refresh 二刷 0 新 11 跳、检索真调命中。
  单测 38/38。
