# 资讯搜索（News Search）· 后端技术实现（Spring Boot :18080）

> 版本：v1.4（2026-09-10；v1.4 = 修正 Q3 终版定案：没有早报/晚报，edition 恒 'telegraph'，无条目映射回填；v1.3 = 关闭 Q3：早/晚报 = 财联社电报流内条目，M4 无渠道前置；v1.2 = 关闭 Q4：C1 隐私红线定案（永不落库/永不持久化、日志严禁打 query/stockCodes 明文）；v1.1 = 回写拍板结论：限流阈值 B6、回填路线 B8、composite SSE C9、LLM 渠道 C10、检索初始参数 C11、基包 API 落位 C12，§8 补本地/正式库数据口径注）
> 读者：后端开发。配套 `docs/news-search-api.md`（契约 v1.1）使用：本文给出现状盘点、search 领域包设计、四个端点的实现路径、通用管道（429/认证/校验）、存量回填与测试拆解。
> 关联：前端仓 `docs/news-search-spec.md`（需求 D1-D10）、前端仓 `docs/news-search-implementation.md`（前端实现）；`docs/news-search-api.md`（接口契约 v1.1，本仓库）
> 核心原则：**复用优先**——announcement/crawler/copilot/llm 四域已有大量现成能力（pgvector 向量库、LLM 责任链、SSE 骨架、限流先例全部在库），新增代码集中在 search 领域包的「检索编排层」，禁止重复建设管线。

---

## 0. 现状盘点（全部经代码验证，勿重复建设）

| 能力 | 位置（stock-calculator-main） | 与搜索的关系 |
|---|---|---|
| 公告元数据+蒸馏摘要 | `announcement` 域：`Announcement` 表（announcementId/title/secCode/secName/seDate/adjunctUrl/summary/status）+ `AnnouncementProcessService`（PDF→结构树切片→蒸馏 200~300 字→数值接地校验→落库） | §2/§5 数据源；summary 即嵌入文本 |
| 公告向量化 | `AnnouncementEmbeddingService`：嵌入文本=summary.trim()，PgVectorStore vector(1024) HNSW cosine，bge-m3 @ Cloudflare；documentId=确定性 UUID（"announcement:"+id，重嵌幂等覆盖）；metadata={announcementId, adjunctUrl, secCode, model}；EmbeddingGate 门控 + ObjectProvider 懒解 | §2 向量召回直接查同一 vector_store |
| 公告采集 | `AnnouncementSyncTask`/`AnnouncementCollectService`：订阅驱动，historySince=2023-01-01，水位+7 天重叠增量 | 语料边界：召回范围=全体用户订阅标的并集（spec 已注明假阴性风险） |
| 订阅闭环 | `AnnouncementSubscriptionController`（/api/announcement/subscriptions，WebConfig 已拦截） | §5 订阅态、语料增长入口 |
| CLS 电报 | `crawler` 域：`cls_article`（id/title/brief/content/ctime/level/type）+ `cls_article_stock`（提及关系）+ `stock` 字典 | §3 数据源；mentions/count7d 现成 |
| CLS 向量化+检索 | `ArticleEmbeddingService`（metadata 含 articleId/ctime）+ `ArticleEmbeddingSearchService.similaritySearch`（SearchRequest→批量回查 ClsArticle 组装 Hit；javadoc 已预留「P1 场景接入时上提门面至 crawler 基包」） | §3 检索上提该门面即可复用 |
| LLM 通道 | `llm` 域 `LlmChainRouter.chat(systemPrompt, userMessage)`：gemini→groq→fallback 责任链，跨域唯一合法入口，400/503 语义 | §4 综合摘要生成通道 |
| SSE 先例 | `copilot`：`CopilotController`（Accept=text/event-stream → SseEmitter）+ `AiChatOrchestrationService.askStream`（delta/error/done 事件、safeSend 断连取消订阅、阶段一失败手工写 JSON 信封回落防 406） | §4 SSE 骨架与内容协商回落范本 |
| 限流先例 | `copilot/util/AiChatRateLimiter`（Redis INCR+EXPIRE 固定窗口、按窗口序号分桶、fail-open、429 穿透降级分支） | §5 SearchRateLimiter 模式 |
| 信封/异常 | `common`：ApiResponse + BusinessException + GlobalExceptionHandler（业务异常 HTTP 200 + 信封 code；AsyncRequestNotUsable/Timeout 已有静默 handler） | 搜索沿用；401 由 AuthInterceptor 直写 |
| 认证 | `auth/config/WebConfig` + `AuthInterceptor`（@RequestAttribute("authUserId")，UUID 文本） | 只差把 /api/search/** 加入拦截列表 |

---

## 1. 新领域包 `search`（包结构按 cls-article-patterns 规约）

```
com.zzh.stock_calculator.search
├── controller/SearchController.java          // 4 端点：announcements / cls / composite / stock-profile
├── service/
│   ├── AnnouncementSearchService.java        // P1
│   ├── StockProfileService.java              // P1
│   ├── ClsSearchService.java                 // P2
│   └── CompositeSearchService.java           // P2
├── dto/SearchDtos.java                       // 请求/响应/SSE 事件 DTO（字段对齐 api 文档）
├── config/SearchConfig.java                  // @EnableConfigurationProperties 注册式（无包扫描）
├── config/SearchProperties.java              // prefix = "search"（§9）
├── util/                                     // 入参校验、citations 组装、adjunctUrl 补前缀等纯函数
└── task/SearchEmbeddingBackfillTask.java     // 存量差集补嵌（§8；或并入 announcement 域 task）
```

**跨域规则**（ModulithVerifyTest 守护；【已拍板 C12】全面采用「基包开放 API / 门面上提」方案，落地时同步更新 ModulithVerifyTest；先例：announcement 域引 crawler 基包 EmbeddingQuotaGuard）：
- announcement 数据：`announcement.repository` 不可直引 → announcement **基包**新增公开查询 API `AnnouncementQueryApi`（实现委托 AnnouncementRepository）：`findAllByAnnouncementIdIn(Collection<String> annIds)`、`findDoneIdsByFilter(secCodes, dateRange, limit)`。
- stock 字典查询：crawler **基包**新增 `StockDirectoryApi`（existsByCode / nameByCode）——仅作 stockName 兜底与可选统计，**不作 stock-profile 的 400 依据**（字典来源于 CLS 每日任务 `parseStockDicts` upsert，覆盖不全；400 判定 = 6 位数字格式，与订阅接口同口径）。
- CLS 检索：crawler **基包**上提 `ArticleEmbeddingSearchService` 门面（其 javadoc 已预留）+ `ClsArticleQueryApi`：`mentionsByArticleIds(...)`（cls_article_stock join stock → stockId/stockName）与 `countByStockCodeSince(...)`（count7d）。
- 向量检索：直接注入 Spring AI `VectorStore` 类型（基础设施 bean，非任何域内部类型，不违反边界）。
- LLM：仅经 `llm` 基包 `LlmChainRouter`。
- 模型名硬编码 `@cf/baai/bge-m3`（EmbeddingProperties 属 crawler 子包，Modulith 红线不可引——announcement 域同款先例；换模型走全局重嵌 R9 流程）。

---

## 2. 公告检索实现（P1，对应 api 文档 §2）

**检索路径**（与 ArticleEmbeddingSearchService 同款基座）：
1. 入参校验（§7）→ `vectorStore.similaritySearch(SearchRequest.builder().query(query).topK(k).similarityThreshold(threshold).build())`；
2. **来源过滤是红线**：vector_store 与 cls 文章共表，现有 metadata 无 kind 标记（两域 model 同为 bge-m3，无法靠 model 区分）。方案：
   - **推荐（配合回填）**：metadata 增加 kind="announcement"（§8 回填重嵌时写入），filterExpression 加 `kind == 'announcement'`；
   - **过渡期（回填前）**：topK 放大 3~5 倍 → 按 metadata.announcementId 回查 AnnouncementQueryApi（DONE + secCode + seDate 过滤）→ 截断 topK。
3. 硬过滤落点：`secCode` 已在 metadata → filterExpression `secCode in [...]` 一步下推 SQL；`seDate` 不在 metadata → 过渡期回查过滤；回填后 annDate（ISO 日期字符串，字典序即时间序）写入 metadata 即可全部下推。
4. DTO 组装：resultId=announcementId（CNINFO annId）、stockId=secCode、stockName=secName、annDate=seDate、title、summary；sourceUrl=adjunctUrl（相对路径补 `http://static.cninfo.com.cn/` 前缀，与 CninfoClient.downloadPdf 同规则；直链为 http 协议，页内新开窗口可接受，https 体验问题仅提示不改）。排序=distance asc（相关度倒序）。
5. `total` = items.size()（api 文档 v1.1 口径）。
6. 降级：EmbeddingGate 未启用 → similaritySearch 返回空集（现成语义）→ 正常返回 total=0 空列表，不报错。

## 3. CLS 检索实现（P2，对应 api 文档 §3）

- 复用上提到 crawler 基包的检索门面：`similaritySearch(query, topK, threshold)` → Hit{articleId, title, brief, level, ctime, score}。
- resultId = String.valueOf(cls_article.id)——**不要**发明 "CLS20260905-088" 之类合成格式（api 文档示例有误导，契约以 C4「消息 id」为准）。
- publishedAt = to_timestamp(ctime) 格式化 "yyyy-MM-dd HH:mm"（东八区）；edition 恒 "telegraph"——【Q3 终版定案】没有早报/晚报，不做条目映射回填。
- summary = brief 优先，缺失时 content 截断 ≤200 字；蒸馏回填后置。
- mentions = ClsArticleQueryApi.mentionsByArticleIds（join stock 取 stockName）。
- dateRange 过滤：ctime 已在 cls metadata（epoch seconds）→ filterExpression 数值区间，或回查过滤，随回填任务一并定。

---

## 4. 综合摘要实现（P2，方案 A SSE【已拍板 C9/Q2】，对应 api 文档 §4）

**流程**（CompositeSearchService，骨架照抄 copilot askStream）：
1. 阶段一（同步、快速失败，仿 copilot beginAsk）：参数校验 → 限流（§5，3 次/10s）→ 双库检索（§2/§3 逻辑，topK 各 8）→ 双库命中为 0 → 直接空态返回（summary=「未检索到足够相关信息」、citations=[]、code 200）。
2. 阶段二（SSE，SseEmitter + TEXT_EVENT_STREAM）：事件序 `meta`（citations：kind/resultId/stockId/date/title，由检索命中直接组装，**先于 delta 发出**保证引用先上屏）→ LLM 流式 token 经 `delta` 转发 → `done`（code 200）→ `emitter.complete()`。
3. Prompt：`LlmChainRouter.chat(system, user)`；system 强约束「只允许基于给定材料作答，不得引入外部知识；材料不足以回答时输出固定话术」；user = 编号材料列表（每条 = summary + 股票/日期元信息）+ 用户 query。材料只进 Prompt，不落库不打日志（C1/copilot contextSummary 同款红线）。
4. 错误：LLM 异常 → `error` 事件（code/message）+ complete；限流 → 阶段一抛 RateLimitedException，**控制器手工写 JSON 信封回落**（Accept 仅 event-stream 时 @ExceptionHandler 的 JSON 会 406，必须照抄 copilot writeJsonFallback 模式）。
5. 超时：SseEmitter timeout 建议 ~35s（**勿照抄 copilot 的 300s**——那是与其聊天 LLM callTimeout=300s 对齐的值，composite 总预算 ≤30s）；客户端断开 → safeSend 模式取消上游订阅 + GlobalExceptionHandler 静默 handler 兜底（均已有）。
6. LLM 渠道【已拍板 C10】：首期统一复用 LlmChainRouter（既有免费链）；质量不足时再在 llm 域注册新渠道（DeepSeek 在 copilot/config 的装配是 copilot 内部实现，**不可跨域直引**）。

## 5. 429 管道（新增，通用；api 文档 §1 限流行的实现依据）

现状缺口：BusinessException 无 data 字段；ApiResponse.fail(code, message) data 恒 null；AiChatRateLimiter / auth RateLimitService 均不产出 retryAfterSeconds。

实现（common + search 两处）：
1. `common/RateLimitedException`：code 固定 429，携带 retryAfterSeconds（long）。
2. `ApiResponse` 增加重载 `fail(int code, String message, T data)`（既有两参重载保持不变，兼容存量）。
3. `GlobalExceptionHandler` 新增 `@ExceptionHandler(RateLimitedException)` → `ApiResponse.fail(429, message, RateLimitData(retryAfterSeconds))`，HTTP 200 + 信封 429，与其他业务错误口径一致。
4. `search/util/SearchRateLimiter`（仿 AiChatRateLimiter，不 import copilot 内部类型）：StringRedisTemplate INCR+EXPIRE 固定窗口、按窗口序号分桶（TTL=2×窗口）、fail-open（Redis 不可用放行 + warn）；BusinessException→RateLimitedException 穿透降级分支。retryAfterSeconds = windowSeconds - (epochSeconds % windowSeconds)。键：`rl:search:{userId}:s:{窗口序号}`（检索）与 `rl:search:{userId}:c:{窗口序号}`（综合）分桶。
5. 阈值【已拍板 B6】：检索 10 次/10s、综合 3 次/10s（SearchProperties，§9，保留可配）。
6. 兼容性：auth/copilot 既有 429（BusinessException，无 data）不受影响；前端对 data 缺省已有兜底分支。

---

## 6. 认证接入（一次性改动）

- `WebConfig.addPathPatterns` 增加 `"/api/search/**"`（AuthInterceptor 由 app.auth.enabled 守护；本地关认证联调时注意端点裸奔仅限 dev）。
- Controller 统一 `@RequestAttribute("authUserId")`（UUID 文本，CustomStatController 同款），search 域不解析 JWT。
- C1 隐私红线【已拍板 Q4，坚决执行】：检索历史**永不落库、永不持久化**——search 域不建任何历史/查询日志表，不写缓存/文件，也不新增 Repository 持久化类型；日志**严禁**打 query/stockCodes 明文——只打参数个数/topK/耗时/命中数；composite 检索材料只进 Prompt。验收对齐 api 文档 §7.2 第 6 条。

## 7. 入参校验与错误文案（BusinessException 400）

| 字段 | 规则 | 400 文案 |
|---|---|---|
| query | trim 后 1~64 字符 | 「检索关键词需 1~64 个字符」 |
| topK | 缺省 10；1~50 | 「检索范围过大」 |
| stockCodes | 逐项 6 位数字码；去重后 ≤50 | 「stockId 非法」/「检索范围过大」 |
| dateRange | YYYY-MM-DD；start ≤ end；跨度 ≤3 年（语料下界 2023-01-01） | 「日期范围无效」 |
| stockId（stock-profile） | 6 位数字码（与订阅接口同正则口径）；格式合法但语料未收录 → 200 空档案 | 「stockId 非法：须为 6 位数字股票代码」 |

- 校验纯函数放 `search/util`（单测友好），Controller 保持薄。
- stock-profile：latestAnnouncements 按 summary 非空过滤取最新 1~3 条（DONE 必有 summary；PENDING 可能已有摘要待补嵌）；clsMention 在 P2 前恒返回 null（前端隐藏区块，契约已约定）。

## 8. 存量核对、回填与 metadata 增强

> 数据口径注：本地库当前为测试数据（announcement 5 条全 DONE；cls 向量仅覆盖 2026-09-09 10:00 之后），正式库向量化已在处理中——差集核对与补嵌以正式库为准，本地仅验证流程。

1. **差集核对**（一次性脚本）：status=DONE 的 announcement id → 确定性 UUID 规则（"announcement:" + id 的 nameUUIDFromBytes；该方法现为 AnnouncementEmbeddingService 包内静态，建议上提 announcement 基包工具复用）→ 对比 vector_store.id，缺失集合即待补嵌。
2. **补嵌**：复用 `AnnouncementEmbeddingService.processAnnouncement`（summary 非空 → 仅补向量化；事务内向量+状态成对写；受 crawler 基包 EmbeddingQuotaGuard 日额度护栏，429 自动熔断到次日 00:00 UTC）。建议做成手动触发 + 低频 cron 增量，不做一次性全量。
3. **metadata 增强（kind + annDate）【已拍板 B8：走 metadata 回填路线，回填后检索一次下推 SQL】**：PgVectorStore 行无原地 update → 重嵌该批（确定性 UUID 幂等覆盖，不产生重复向量）；成本 = 存量条数，额度护栏自然分摊多日。过渡期检索按 §2 步骤 2 的放大+回查兜底，回填完成后 filterExpression 收紧为 `kind == 'announcement'`。
4. **共表红线**：cls 与 announcement 共用 vector_store，所有检索必须带来源过滤——写进 SearchController javadoc，防后续新端点踩坑。

---

## 9. 配置键（application.yml，prefix=search，SearchConfig 注册）

```yaml
search:
  rate-limit:
    search-window-seconds: 10
    search-max-per-window: 10
    composite-max-per-window: 3
  retrieval:
    default-top-k: 10
    max-top-k: 50
    default-threshold: 0.3      # 检索初始参数【已拍板 C11：threshold=0.3 / topK=10，联调期再校准】；crawler 侧仍 0.5，搜索独立
    kind-filter-enabled: false  # 过渡期 false：回填完成后切 true 下推 SQL
  composite:
    llm-timeout-seconds: 30
    retrieval-top-k: 8
  backfill:
    enabled: false              # 公告向量 metadata 回填（kind/annDate，B8）：默认关闭，需要时手动开启一次
    cron: '0 40 2 * * *'
```

> **回填任务落点**：`announcement/task/AnnouncementEmbeddingBackfillTask`（不在 search 域——
> 复用 announcement 内部的 `processAnnouncement` 蒸馏管道，entity/内部类型不可跨域）。
> 差集核对按 `vector_store.metadata->>'kind'='announcement'` 对全量 DONE 公告求补集。
> 正式库向量化完成后：开 `search.backfill.enabled=true` 跑一轮补齐 kind/annDate →
> 核对无差集后关回，并将 `search.retrieval.kind-filter-enabled` 切 `true` 启用 SQL 下推。
> （2026-09-10：上述配置块已同步落入 `application.yml` 末尾。）

## 10. 测试与验收

- **单测**：校验纯函数（边界：query 1/64、topK 50/51、stockCodes 50/51、dateRange 跨度）；SearchRateLimiter（窗口滚动、retryAfterSeconds 计算、fail-open）；composite prompt 组装（mock LlmChainRouter，断言「编号材料 + 红线话术」结构）；DTO 组装（adjunctUrl 前缀补齐、publishedAt 格式化）。
- **IT**（本地库，环境变量口令见 workflow skill）：向量召回 + secCode/seDate 过滤 + summary 非空过滤；空命中空态；SSE 事件序（meta→delta…→done / error）；429 信封带 data.retryAfterSeconds；隐私断言：检索全链路日志输出不含 query/stockCodes 明文（C1/Q4）。
- **ModulithVerifyTest**：search 只 import 各域基包公开类型（新增 AnnouncementQueryApi / StockDirectoryApi / ClsArticleQueryApi + 上提的检索门面）。
- **联调**：按 api 文档 §7.2 清单前后端各跑一遍；前端已有 mock 全链路与 A8/A9/A10 验收可对拍。
- 全量验证命令（TaskServiceTest 永远排除）：`./mvnw test '-Dtest=!TaskServiceTest' '-DfailIfNoTests=false'`

## 11. 里程碑（粗估）

| 里程碑 | 内容 | 粗估 |
|---|---|---|
| M1 通用管道 | RateLimitedException + fail 重载 + handler、SearchRateLimiter、WebConfig 接入、SearchProperties、校验纯函数、DTO 骨架 | 0.5d |
| M2 公告检索 | §2 全链路（过渡期放大+回查）+ 差集核对脚本 + 补嵌任务 + metadata 增强 | 1~2d |
| M3 档案卡聚合 | StockProfileService（latest 1~3 条 + clsMention=null）+ StockDirectoryApi | 0.5d |
| M4 CLS 检索 | crawler 基包门面上提 + ClsArticleQueryApi + §3 组装（依赖 edition 映射定义） | 0.5~1d |
| M5 综合摘要 | §4 SSE 全链路 + 限流接入 + 命中不足空态 | 1d |

依赖关系：M2 阻塞 P1 前端联调；M3 可与 M2 并行；M4/M5 属 P2（Q3 已终版定案：无早/晚报，M4 无渠道前置，edition 恒 telegraph）。embedding 未启用环境（EMBEDDING_ENABLED=false）下所有检索端点返回空集而非报错，前端空态兑底。
