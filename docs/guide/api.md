---
status: active
updated: 2026-09-26
---

# 选股引导（Guide）· 接口文档（前端对接）

> 版本：v1.0（2026-09-26）。读者：前端开发（对接 :18080 `/api/guide/*` 两端点）。
> 状态：已实现上线（后端 473 单测全绿 + 真实冒烟通过，契约详见 [design](design.md) §四/§七）。
> 关联：[design](design.md)（两步向导流程/决策记录/红线）；聊天入口（copilot 经 dispatch）对前端透明，本文件只覆盖 REST 面板。

## 0. 通用约定

- **Base URL**：`http://<host>:18080`，与 `/api/search` 等同应用。
- **鉴权与限流**：`/api/guide/**` **不挂**登录拦截、无频控（design D10：本端点族同时是 orchestration 工具面，ToolInvoker 无用户会话）。前端无需携带登录态，也未接入既有 RateLimiter。
- **响应信封**：统一 `ApiResponse`——`{"code":200,"message":"success","data":{...}}`。**HTTP 状态恒为 200**，前端必须判 `body.code`（业务错误 code=400，系统异常 code=500，见 §3）。
- **时间窗口径**：`days` = **滚动时间窗**（rolling：服务端 `since = now − days×86400s`，按请求时刻回溯，**非自然日零点锚定**——「近 7 天提及数」即含当前时刻在内往前 168h），缺省 7，范围 1-30（越界静默钳制，不报错）。
- **nextStep / nextSteps 恒在 data 首位**：这是给调用方的流程指令（后端 keep_head 截断安全位设计），向导 UI 可直接按其驱动步骤切换与提示文案。
- **聊天入口零改动**：copilot 聊天经 orchestration dispatch 已可用（工具 `main.guide.analyze_message` / `main.guide.stock_brief`），走既有 `/api/copilot` 会话链路，前端无需为本功能新增任何聊天侧调用。
- **部署与 CORS**：`/api/guide/**` **不挂** `@CrossOrigin`——对齐 search/broker 数据端点多数派，前端经**同源或反向代理**访问（与调用 `/api/search` 同一部署方式即可）；auth/import 的 CORS 是 Bearer 无 cookie 特例，不适用于本端点族。当前部署不对公网开放（暴露面评估见 design D13）。

### 0.1 公共 DTO 时间字段口径（防单位误读）

| 字段 | 出现于 | 格式/单位 | 前端换算 |
|---|---|---|---|
| `ctime` | ArticleBrief（candidates[].sampleArticles / relatedArticles / clsMention.articles） | **epoch 秒**（10 位，`cls_article.ctime` 口径） | 转 `Date`/毫秒需 **×1000**，勿按毫秒直读 |
| `annDate` | announcements[].annDate | 字符串 `yyyy-MM-dd`（公告披露日，公告域原文口径） | 直接展示，无需时区换算 |
| `articleId` | ArticleBrief | Long，`cls_article.id` 文本数值（C4 口径，勿合成编号） | 仅作标识，当前无按 id 回源正文的公开端点（见 §6） |

## 1. Step 1 消息→候选股：`POST /api/guide/analyze-message`

用户听到一条消息（新闻/传闻转述）、给题材/概念、或给股票代码/名称时调用。

### 1.1 请求（JSON body）

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| message | string | 是 | 消息/题材/代码原文，≤500 字（超长 400）；裸 6 位代码与消息内嵌代码均支持（服务端词典归一化） |
| days | int | 否 | 候选依据与提及计数的时间窗（天），缺省 7，范围 1-30 |

### 1.2 响应 data

| 字段 | 类型 | 说明 |
|---|---|---|
| nextStep | string | 流程提示文案（恒首位；v1.1 起**展示安全**——不含工具名，可直接渲染给终端用户） |
| nextAction | string | **分支判据唯一来源（恒次位）**：`present_candidates`=有候选请用户选定；`clarify`=空候选需追问。前端以本字段判分支，不得仅凭 candidates 是否为空推断 |
| candidates | Candidate[] | 候选清单，≤8；STOCK 直锚在前、题材扩展按近期提及文章数降序；**可能为空**（未锚定到任何股票，配合 relatedArticles/keywords 走澄清） |
| candidates[].stockId | string | 系统股票 ID（字典键形态，如 `sh600519`）——**Step 2 的 stockId 用它** |
| candidates[].stockName | string | 股票名称（字典兜底可能为空串，UI 需容错） |
| candidates[].hitType | string | `STOCK`=消息直接锚定个股；`SUBJECT`=经题材两跳扩展 |
| candidates[].hitName | string | 命中来源名（STOCK=公司名；SUBJECT=题材名），可用于 UI 标注「来自题材：固态电池」 |
| candidates[].recentMentionCount | long | 近窗口被电报提及的文章数（热度依据） |
| candidates[].sampleArticles | ArticleBrief[] | 依据样例（≤2 条，仅 title+ctime，无正文） |
| entities | EntityHit[] | 锚定结果回显：`anchorType` ∈ STOCK/CLS_SUBJECT；**null=未锚定自由词**（已丢弃不入候选，仅展示） |
| keywords | string[] | LLM 抽取的未锚定关键词（澄清素材） |
| relatedArticles | ArticleBrief[] | 空候选时的兜底相关电报（≤5 条，按 keywords 检索）；候选非空时恒为空数组 |
| llmDegraded | boolean | true=LLM 抽取链路降级（结果仅来自词典匹配），UI 可提示「AI 解析暂不可用」 |

### 1.3 样例

请求 `{"message":"600519","days":7}`（裸代码直查）：

```json
{"code":200,"message":"success","data":{
  "nextStep":"向用户呈现候选清单（附近期提及数与样例依据），请其选定关注对象后查看个股档案。",
  "nextAction":"present_candidates",
  "candidates":[{"stockId":"sh600519","stockName":"贵州茅台","hitType":"STOCK","hitName":"贵州茅台",
    "recentMentionCount":0,"sampleArticles":[]}],
  "entities":[{"name":"贵州茅台","anchorType":"STOCK","anchorId":"sh600519"}],
  "keywords":[],"relatedArticles":[],"llmDegraded":false}}
```

请求 `{"message":"固态电池","days":30}`（题材两跳，节选）：

```json
{"data":{"nextStep":"向用户呈现候选清单……","nextAction":"present_candidates",
  "candidates":[{"stockId":"sz002594","stockName":"比亚迪","hitType":"SUBJECT","hitName":"固态电池",
    "recentMentionCount":3,
    "sampleArticles":[{"articleId":501234,"title":"……电报标题……","ctime":1758750000}]}], ...}}
```

候选为空（含澄清兜底）：

```json
{"data":{"nextStep":"候选为空：请补充公司名、行业或大致时间，我可以再帮你找；下方相关电报供参考。",
  "nextAction":"clarify",
  "candidates":[],"entities":[{"name":"储能","anchorType":null,"anchorId":null}],"keywords":["储能"],
  "relatedArticles":[{"articleId":201,"title":"储能电报标题","ctime":1758750000}],"llmDegraded":false}}
```

## 2. Step 2 个股引导档案：`GET /api/guide/stock-brief`

用户从候选中选定（或直接指定）某只股票时调用；**独立可用**，不必先走 Step 1。

### 2.1 请求（query）

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| stockId | string | 是 | Step 1 候选的 stockId；也接受裸 6 位代码（`600519`）与腾讯形态（`sh600519`，大小写不限），服务端归一化到字典键；>32 字符 400 |
| days | int | 否 | 时间窗（天），缺省 7，范围 1-30 |

### 2.2 响应 data

| 字段 | 类型 | 说明 |
|---|---|---|
| nextSteps | string[] | 动作建议（恒首位，当前 3 条固定文案）：技术面深挖 / 设提醒（登记联动待 P2 设计）/ 回溯消息来源 |
| stockId / stockName | string | 归一化后的 ID 与名称（名称兜底链=公告 secName→字典→空串） |
| clsMention.count | long | 近窗口被电报提及的文章数 |
| clsMention.articles | ArticleBrief[] | 近期提及文章头（≤5 条，ctime 倒序，无正文） |
| subjects | SubjectItem[] | 近窗口题材归属标签（≤5，articleCount 降序）：subjectId/subjectName/articleCount |
| announcements | AnnouncementItem[] | 近期公告蒸馏摘要（≤3 条，annDate 倒序）：annDate/title/summary |
| techSnapshot | TechSnapshot \| null | 日线级技术面快照（经 dispatch 调 mcp 指标/位带；通道不可用时为 null，档案其余部分不受影响） |

**techSnapshot 结构**：`lastDate`/`lastClose`/`changePct`（快照日收盘与涨跌幅）、`signals`（指标文字信号 ≤8 条：MA 排列/金叉死叉/超买超卖）、`nearestSupport`/`nearestResistance`（最近支撑/压力位带各一档：priceLow/priceHigh/type（pivot|swing|volume）/distPct）。

### 2.3 样例

请求 `GET /api/guide/stock-brief?stockId=600519&days=7`：

```json
{"code":200,"message":"success","data":{
  "nextSteps":["techSnapshot 是日线级粗粒度快照，深挖形态可让 AI 算具体指标或画 K 线复核",
               "确认关注价值后说「帮我设个提醒」跟踪后续（提醒登记联动待 P2 设计）",
               "需要回溯消息来源时，可引用 clsMention.articles 里的电报标题追问"],
  "stockId":"sh600519","stockName":"贵州茅台",
  "clsMention":{"count":0,"articles":[]},
  "subjects":[],"announcements":[],
  "techSnapshot":{"lastDate":"2026-09-24","lastClose":1237.0,"changePct":-1.14,
    "signals":["KDJ 金叉（K 上穿 D，近 3 根内）","MA 空头排列（5<20<60）"],
    "nearestSupport":{"priceLow":1235.98,"priceHigh":1235.98,"type":"swing","distPct":-0.08},
    "nearestResistance":{"priceLow":1241.39,"priceHigh":1241.39,"type":"pivot","distPct":0.36}}}}
```

> count=0 / 空数组属正常响应（窗口内无电报提及或公告），**不是错误**；UI 按「近窗口无数据」呈现即可。未收录股票同样是 200 + 空聚合 + 空名（后端不 404）。

## 3. 错误码

| body.code | 场景 | 前端处理建议 |
|---|---|---|
| 200 | 成功（含空候选/空档案的正常业务态） | 判 nextStep/candidates 分支 |
| 400 | message 为空或超 500 字；stockId 为空或超 32 字符 | 表单校验提示 |
| 500 | 系统未知异常（LLM 链路失败**不会**走到这里——Step 1 内部已降级为 llmDegraded=true 的 200 响应） | 通用错误提示重试 |

> 注意：LLM 免费链路失败/超时对前端表现为 `code=200 + llmDegraded=true`（词典路径结果照常返回），**不是**错误分支。

## 4. 两种调用形态（前端选型参考）

| 形态 | 链路 | 前端工作量 | 适用 |
|---|---|---|---|
| 聊天引导（已可用） | `/api/copilot` → copilot LLM 经 dispatch 自主调用 guide 工具 | **零** | 对话式体验，用户自然语言驱动 |
| REST 向导（本文件 §1/§2） | 前端直调 `/api/guide/*` | 向导 UI 两步页面 | 结构化卡片式引导（候选清单/档案卡），确定性展示 |

两种形态可共存：向导页产生的候选也可引导用户去聊天窗追问。

## 5. 对话界面路由约定（chat vs REST 通道）

裁决规则：**按输入形态路由，不按内容语义路由**——前端零意图判断（语义理解是 LLM 侧的事，前端不预判「这句话 LLM 会不会接管 guide 工具」）。

| 场景 | 通道 |
|---|---|
| 输入框自由文本（长句消息转述、题材、纯代码「600519」均含） | **copilot**（LLM 自主决定调 guide 工具或其它工具；系统提示词已注入引导约定） |
| 候选卡片点「查看档案」/ 向导页步进提交 / 档案卡改时间窗刷新 | **REST 直调** §1/§2（参数现成，快且确定） |
| 档案卡按钮「算下形态」「设个提醒」 | **copilot**（LLM 经 dispatch 编排到经纪人/通知者） |

两通道握手点：copilot 回复中的候选 → 前端渲染成卡片 → 用户点卡片 → 前端拿卡片 `stockId` **直调** §2 渲染档案卡（LLM 负责「找出候选」，结构化点击负责「消费候选」）。

### 5.1 向聊天 handoff 的上下文携带（预填约定）

跳转 copilot 时**预填输入框**（用户可见可改，零后端改动；不用 promptHints 隐注入——那是页面快照通道，语义不符）：

| 触发点 | 预填文案模板 |
|---|---|
| 档案卡「算下形态」 | `算下 {stockName}({stockId}) 的形态` |
| 档案卡「设个提醒」 | `帮我设个提醒：{stockName}({stockId}) `（用户补时间/条件） |
| 空候选引导追问（nextAction=clarify） | `关于「{keywords 顿号连接}」，帮我找找相关股票`（可附 relatedArticles 标题供 LLM 引用） |

> **LLM 自动接管边界**：经聊天调 analyze-message 时，慢路径（长句，内部免费 LLM 抽实体）受调用链超时梯队约束（ToolInvoker 8s < dispatch 10s < copilot HTTP 15s，而端点内部 LLM 预算 20s）——免费链路慢时聊天侧 8s 先超时，REST 直调可吃满 20s；快路径（代码/公司名/题材短查询）毫秒级无此问题。

## 6. 演进注记

- **样例电报正文回源**：`ArticleBrief` 仅 title+ctime；按 articleId 取正文的公开端点暂缺（P2 候选 `GET /api/guide/article/{id}` 或复用 search 域），UI 一期展示标题即可。
- 本文件随 guide 域对外契约变更同轮更新（版本行追加变更摘要）；契约分歧以代码为准并回改文档。
