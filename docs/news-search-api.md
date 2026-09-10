# 资讯搜索（News Search）· 接口文档（后端开发对接）

> 版本：v1.5（2026-09-10；v1.5 = 修正 Q3 终版定案：没有早报/晚报，edition 恒 'telegraph'，无条目映射回填；v1.4 = 关闭 Q3：早/晚报 = 财联社电报流内条目，无新增获取与合规评估；v1.3 = 关闭 Q4：C1 隐私红线定案；v1.2 = 关闭 Q2：composite 拍板 SSE（方案 A）；v1.1 = 采纳后端评审：修正 §0.2 订阅接口返回结构、§1 HTTP 状态口径、§6 改写为复用既有管线，关闭 Q1/Q5/Q6）
> 读者：后端开发（Spring Boot :18080，与 /api/auth 同应用）。本文给出检索类四个新接口的完整契约、通用约定、数据依赖与联调说明；前端按此契约开发并在就绪前使用 mock。
> 关联：前端仓 `docs/news-search-spec.md`（需求 D1-D10）、前端仓 `docs/news-search-implementation.md`（前端实现）、`docs/news-search-backend-implementation.md`（后端技术实现，本仓库）；已上线接口 `POST/DELETE/GET /api/announcement/subscriptions`（订阅闭环，本文件 §0.2 摘要）
> 状态：契约定稿待后端排期；P1 依赖 §2/§5，P2 依赖 §3/§4

---

## 0. 给后端的一页纸摘要

### 0.1 现状与诉求

- 前端搜索页（route `/news`）已按 mock 设计完毕，等四类真实接口：
  1. **公告摘要检索**（P1）：在已入库的 CNINFO 公告摘要上做关键词/向量检索；
  2. **CLS 电报检索**（P2）：财联社电报检索（Q3 终版定案：无早报/晚报）；
  3. **AI 综合摘要**（P2）：双库检索结果交给 LLM 串联成 2~3 句综合摘要 + 引用；
  4. **股票档案卡聚合**（P1）：按股票聚合最新公告 + CLS 提及，一次请求出卡片。
- 前端已有能力（后端无需重复建设）：登录会话（WebConfig 已拦截 `/api/auth/**`，需把 `/api/search/**` 纳入同样拦截）、ApiResponse 统一信封、Bearer JWT。

### 0.2 既有订阅接口摘要（已上线，前端已接入）

| 方法与路径 | 说明 | body/参数 | 成功 data |
|------------|------|-----------|-----------|
| `POST /api/announcement/subscriptions` | 订阅（首次触发 CNINFO 异步首拉） | `{"stockId":"600745"}` | `true`=新建（触发首拉事件）/`false`=已存在幂等跳过 |
| `DELETE /api/announcement/subscriptions/{stockId}` | 取消订阅 | - | `true`=删除成功 / `false`=本就未订阅 |
| `GET /api/announcement/subscriptions` | 查询订阅列表（创建时间倒序） | - | `{"items":[{"stockId":"600745","orgId":"…","createdAt":"2026-09-01T10:00:00+08:00"}]}` |

错误约定已生效：400（BusinessException，如「stockId 非法」「订阅数量已达上限」）、401 未认证——搜索接口沿用同一套语义。

> **v1.1 更正**：v1.0 把三个端点的成功 data 全部写错（写成了 null / 字符串数组）。上表为后端实际返回（`AnnouncementSubscriptionController` 实测口径）；前端 `fetchSubscriptions` 已按三种形状兼容解析（顶层 `string[]` / `{stockId}[]` / `{items:[...]}`）。

### 0.3 关键约束（需求侧决定，不可协商）

| # | 约束 |
|---|------|
| C1 | **持仓数据不出后端**：用户的持仓明文只在前端（E2EE 架构）。「持仓限定」由前端把 6 位股票代码集合作为 `stockCodes` 请求参数传入；后端无状态、不落库（含检索历史，一期不存任何查询日志中的 query/stockCodes 与用户关联） |
| C2 | **摘要而非原文**：列表/卡片只返回 2~3 句提炼摘要（`summary` 字段），不做长文返回；原文以 `sourceUrl` 外链巨潮 |
| C3 | **检索范围由前端决定**：`scope` 不作为参数传给后端——前端按 Filter 分别调 §2/§3/§4 三个接口，后端只按接口职责返回 |
| C4 | 结果需携带稳定 `resultId`（公告用 annId、CLS 用消息 id），前端以此构建 Copilot 上下文锚点 |
| C5 | 综合摘要建议 SSE 流式（§4），若一期不做流式则同步返回但超时上限 30s |

---

## 1. 通用约定

| 项 | 约定 |
|----|------|
| Base Path | `/api/search`（与 `/api/auth`、`/api/announcement` 同一 Spring Boot 应用同源部署） |
| 认证 | `Authorization: Bearer <JWT>`（同既有模块）；WebConfig 将 `/api/search/**` 纳入登录拦截；未认证返回 401 |
| 响应信封 | 恒 ApiResponse：`{"code": number, "message": string, "data": T}`；成功 `code===200`；HTTP 状态口径（v1.1 更正）：业务错误经 GlobalExceptionHandler 返回 **HTTP 200 + 信封 code**（400/429/500），前端以信封 code 分支、不依赖 HTTP 状态；例外：未认证 401 由 AuthInterceptor 直写 **HTTP 401 + 信封 401**。搜索接口沿用现状，不做 HTTP 状态对齐改造 |
| 业务错误 | 400 BusinessException，`message` 必须是用户可读中文（前端直接展示，如「检索关键词过长」） |
| 限流 | 429 + `data.retryAfterSeconds`（建议：检索类单用户 10s 窗口 ≤ 10 次；综合摘要 ≤ 3 次）；message 如「请求过于频繁，请稍后重试」。**v1.1 注：`data.retryAfterSeconds` 属新增管道**——现有限流器（auth/copilot）抛 429 时 data 恒 null，需 RateLimitedException + 专用 handler + ApiResponse.fail 带 data 重载，实现见 backend-implementation §5 |
| 编码/超时 | UTF-8 JSON；后端检索类建议 P95 ≤ 800ms；综合摘要同步 ≤ 30s 或走 SSE |
| 分页 | 一期不分页：`topK` 控制条数（默认 10，上限 50，超出返回 400「检索范围过大」） |

---

## 2. 公告摘要检索（P1）

`POST /api/search/announcements`

**请求体：**

```json
{
  "query": "对赌协议",
  "stockCodes": ["600745", "601318"],
  "dateRange": { "start": "2026-08-11", "end": "2026-09-10" },
  "topK": 10
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| query | string | 是 | 关键词/自然语言短句，1~64 字符 |
| stockCodes | string[] | 否 | 6 位数字码集合；**提供时为硬过滤**（仅返回这些股票）；缺省 = 不限股票 |
| dateRange | object | 否 | 公告发布日期闭区间，YYYY-MM-DD |
| topK | number | 否 | 缺省 10，上限 50 |

**成功 data：**

```json
{
  "total": 2,
  "items": [
    {
      "resultId": "AN20260901XXXX",
      "stockId": "600745",
      "stockName": "闻泰科技",
      "annDate": "2026-09-01",
      "title": "关于控股股东签署补充协议的公告",
      "summary": "控股股东与战投方签署补充对赌协议，触发条件为 2026 年度半导体业务营收不低于 180 亿元……协议第二阶段将对赌期限延长一年。",
      "sourceUrl": "http://www.cninfo.com.cn/new/disclosure/detail?annId=AN20260901XXXX"
    }
  ]
}
```

**要求**：
- `total` 一期口径 = `items.length`（topK 截断后返回条数）；前端列表头建议「匹配到 N 条（展示前 topK 条）」，精确命中总数 P2 再评估。
- 入参校验（超限 400）：`stockCodes` 逐项 6 位数字码、去重后 ≤ 50；`dateRange` 跨度 ≤ 3 年（语料下界 2023-01-01）。
- `sourceUrl` 直接返回 `adjunctUrl`（存量相对路径时补 static.cninfo.com.cn 前缀，与下载逻辑同规则；Q6 就此关闭）。
- 排序：相关度倒序；前端「持仓公告」范围会按 `stockName + annDate` 二次排序展示（后端无需关心）。
- `summary`：入库时由 LLM/抽取生成的 2~3 句摘要；若存量数据摘要缺失，返回截断的首段正文（≤ 200 字）并在 §6 回填后自动改善。
- `stockName` 必须返回（前端不查行情接口补名称）。

---

## 3. CLS 电报检索（P2）

`POST /api/search/cls`

**请求体：**

```json
{
  "query": "半导体 板块",
  "dateRange": { "start": "2026-09-04", "end": "2026-09-10" },
  "topK": 10
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| query | string | 是 | 同 §2 |
| dateRange | object | 否 | 发布时间闭区间（按日期） |
| topK | number | 否 | 同 §2 |

**成功 data：**

```json
{
  "total": 3,
  "items": [
    {
      "resultId": "CLS20260905-088",
      "publishedAt": "2026-09-05 07:32",
      "edition": "telegraph",
      "title": "半导体板块获政策利好 设备与材料环节多家公司受益",
      "summary": "半导体板块利好政策出台，涉及设备与材料环节多家公司……",
      "mentions": [
        { "stockId": "600745", "stockName": "闻泰科技" }
      ]
    }
  ]
}
```

- `edition`：恒 `telegraph`（Q3 终版定案：无早报/晚报，语料即财联社电报；字段保留仅为契约稳定，前端无需按枚举分支）。
- `mentions`：该条快讯提及的 A 股公司（从正文实体识别抽取）；可为空数组。前端点击 chip 会以该股票发起档案卡查询。

## 4. AI 综合摘要（P2）

`POST /api/search/composite`

双库检索（§2 + §3 逻辑）后由 LLM 生成一段 2~3 句串联摘要 + 引用来源。

**请求体：**

```json
{
  "query": "本周半导体板块有哪些值得关注的动向？",
  "stockCodes": ["600745"],
  "dateRange": { "start": "2026-09-04", "end": "2026-09-10" }
}
```

**方案 A（已拍板 v1.2）：SSE 流式**
- 响应 `Content-Type: text/event-stream`；事件序列：
  - `event: meta` → `data: {"citations":[{"kind":"announcement","resultId":"AN...","stockId":"600745","date":"2026-09-01","title":"..."}]}`
  - `event: delta` → `data: {"text":"半导体板块本周……"}`（多次，前端渐进追加）
  - `event: done` → `data: {"code":200,"message":"ok"}`
  - 错误：`event: error` → `data: {"code":429,"message":"请求过于频繁，请 8 秒后重试","retryAfterSeconds":8}` 后关闭流
- 断连重试由前端负责；`meta` 事件先于 delta 发出，保证引用先上屏。

**方案 B（降级）：同步 JSON**
- 恒 ApiResponse，`data: { "summary": "……", "citations": [同上] }`；后端内部超时 30s，超时返 504 信封。

**LLM 约束**：只允许基于检索命中内容作答，不得引入外部知识；命中不足时返回 `summary` 为「未检索到足够相关信息」+ 空 citations（code 仍 200，前端按空态展示）。

> 前端已同时实现 SSE 与同步 JSON 两种解析（镜像 copilotService 内容协商回落），Q2 的方案选择**不再阻塞前端排期**，仅影响综合摘要的渐进渲染体验。

## 5. 股票档案卡聚合（P1）

`GET /api/search/stock-profile?stockId=600745`

一次请求返回搜索页「确定性档案卡」所需全部数据。

**成功 data：**

```json
{
  "stockId": "600745",
  "stockName": "闻泰科技",
  "latestAnnouncements": [
    {
      "annId": "AN20260908XXXX",
      "annDate": "2026-09-08",
      "title": "拟增加半导体产能投资",
      "summary": "拟投资 5 亿元增加先进封装产能……业绩对赌协议进入第二阶段。"
    }
  ],
  "clsMention": {
    "count7d": 3,
    "items": [
      { "publishedAt": "2026-09-05 07:32", "summary": "半导体板块利好政策出台，涉及相关公司……" }
    ]
  }
}
```

| 字段 | 说明 |
|------|------|
| latestAnnouncements | 最新 1~3 条，按 annDate 倒序；必须有 `summary`（缺失时截断首段 ≤ 200 字） |
| clsMention | P2 数据，CLS 就绪前可返回 `null`（前端隐藏该区块）；`count7d` = 近 7 天提及次数 |
| 未知 stockId | **格式非法（非 6 位数字）→ 400「stockId 非法：须为 6 位数字股票代码」**（与订阅接口同款正则口径，AnnouncementSubscriptionService.STOCK_ID_PATTERN）；格式合法但语料未收录 → **200 + 空 latestAnnouncements + clsMention=null**（stockName 尽力返回：announcement.secName → stock 字典 → 空串由前端行情补）。不做字典存在性 400——stock 字典来源于 CLS 每日任务，覆盖不全；latestAnnouncements 按 summary 非空过滤（DONE 必有 summary） |

降级约定：若后端短期排不出聚合接口，前端将自行组合 §2 检索（`stockCodes=[stockId], topK=3`）+ 本地订阅态 + 行情接口拼装档案卡；§5 就绪后切回，前端已预留同一返回类型。

---

## 6. 数据依赖与建设建议（后端侧）

### 6.1 公告摘要 embedding 管线（v1.1 改写：基本已建成，勿重复建设）

**现状（已上线，announcement 域）**：
- 采集：订阅驱动（historySince=2023-01-01，水位+7 天重叠增量），`announcement` 表含 announcementId/title/secCode/secName/seDate/adjunctUrl/summary/status。
- 摘要：入库时 PDF→切片→LLM 蒸馏 200~300 字纯事实摘要 + 数值接地校验落库（AnnouncementProcessService）；Q5 关闭。
- 向量化：嵌入文本 = summary（非 title+summary）→ PgVectorStore（vector(1024)、HNSW cosine、bge-m3 @ Cloudflare）；metadata = announcementId/adjunctUrl/secCode/model，documentId = 确定性 UUID（"announcement:" + id，重嵌幂等覆盖）；选型已定，Q1 关闭。
- 断点续传：summary 非空仅补向量化；状态机 PENDING → DONE/FAILED；额度受 crawler 基包 EmbeddingQuotaGuard 护栏。

**P1 剩余工作**：
1. 检索服务（§2）：向量召回 + metadata/SQL 过滤 + DTO 组装（backend-implementation §2）；
2. 存量核对与回填：DONE 但 vector_store 无对应行的差集补嵌；metadata 需补 kind/annDate 过滤字段（backend-implementation §8）；
3. 语料覆盖边界：召回范围 = 全体用户订阅标的并集，未订阅持仓会假阴性（spec 已注明 + 空态引导订阅）。

### 6.2 CLS 数据源（v1.1 改写：电报已在库；v1.5 终版定案：无早报/晚报）

**现状（已上线，crawler 域）**：
- 财联社电报抓取（sign/roll 签名轮询）持续入库：`cls_article`（id/title/brief/content/ctime/level/type）。
- 提及关系已抽取：`cls_article_stock`（article_id × stock_id）+ `stock` 字典——§3 `mentions` 与 §5 `count7d` 的数据基础现成。
- 向量化：`cls_article_embedding` 状态表 + 同一 PgVectorStore（与公告共表，靠 metadata 区分来源，检索必须带来源过滤）；检索门面 `ArticleEmbeddingSearchService` 已有（注释预留「P1 场景接入时上提至 crawler 基包」）。

**P2 剩余工作**：
1. edition【Q3 终版定案 v1.5】：没有早报/晚报，§3 edition 恒 'telegraph'，不做条目识别与映射回填；
2. summary 策略：一期 `summary` 降级用 `brief`（缺失时截断 content ≤200 字），蒸馏回填后置评估。

### 6.3 LLM 通道

- LLM 通道统一走 llm 域 `LlmChainRouter.chat(system, user)`（跨域唯一合法入口，免费渠道链）；SSE 骨架与限流复用 copilot 先例（SseEmitter、delta/error/done 事件、AiChatRateLimiter 固定窗口模式），详见 backend-implementation §4/§5。

## 7. 前端代理与联调说明

### 7.1 代理（前端自理，后端无需关心）

- 本地开发：vite 代理 `/api/search` → `devUpstreams.auth`（即 localhost:18080）。
- 线上：Vercel Edge Middleware 转发 `/api/search/*` → 同一上游，与 `/api/auth` 同路径约定（保留前缀）。
- 后端只需保证同源部署下 `/api/search/**` 可达 + WebConfig 纳入认证拦截。

### 7.2 切换与联调

- 前端 `searchService` 内 `USE_MOCK` 常量置 false 即切真实接口；接口未就绪期间 mock 不影响其余功能。
- **联调用例清单**（前后端各跑一遍）：
  1. §2：正常命中 / `stockCodes` 硬过滤生效 / dateRange 边界 / topK=50 上限与 51 拒绝 / 空 query 400；
  2. §5：已知股票 / 未知股票 400 / `clsMention=null` 时前端隐藏区块；
  3. 未认证 401 → 前端弹会话过期；
  4. 限流 429 → 前端展示 retryAfterSeconds 倒计时；
  5. §4：SSE 事件序（meta→delta…→done）/ 断连重试 / 命中不足空态；
  6. 隐私验收（C1/Q4 定案）：服务端确认检索历史永不落库、永不持久化，日志不含 query/stockCodes 明文及其与用户的任何关联记录。

## 8. 开放问题（待后端确认）

| # | 问题 | 影响 |
|---|------|------|
| Q1 | ~~embedding 模型与向量库选型~~ **已关闭（v1.1）**：bge-m3(1024 维) @ Cloudflare + pgvector/HNSW 已上线（与 cls 共用 vector_store）；剩余仅存量回填核对 | - |
| Q2 | ~~§4 走 SSE（方案 A）还是同步（方案 B）~~ **已关闭（v1.2）**：拍板 SSE（方案 A），按后端实现文档 §4 实现；前端双解析保留作内容协商回落 | - |
| Q3 | ~~CLS 数据源渠道与合规评估~~ **已关闭（v1.4；v1.5 终版修正）**：没有早报/晚报——CLS 语料即财联社电报，内容已在库并已向量化，无新增获取与合规评估；§3 edition 恒 'telegraph'，不做映射回填 | - |
| Q4 | ~~检索历史是否永远不落库（C1）还是允许匿名聚合统计~~ **已关闭（v1.3）**：坚决执行 C1 隐私红线——检索历史永不落库、永不持久化（不建历史/日志表，不写缓存/文件），日志严禁打 query/stockCodes 明文，只记参数个数/topK/耗时/命中数；匿名聚合统计不做 | - |
| Q5 | ~~summary 的生成时机~~ **已关闭（v1.1）**：入库时蒸馏生成已实现（AnnouncementDistillService + 数值接地校验） | - |
| Q6 | ~~公告 sourceUrl 的稳定外链格式~~ **已关闭（v1.1）**：直接返回 announcement.adjunctUrl（存量相对路径补 static.cninfo.com.cn 前缀） | - |
