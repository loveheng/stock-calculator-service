# Copilot AI 聊天接口文档 v1.0

> 生成时间：2026-09-02  
> 基准分支：P1 实现版本（DeepSeek 渠道）  
> Base URL：`/api/copilot`  
> 认证方式：复用项目统一鉴权（Spring Security Bearer Token，通过拦截器注入 `Authentication` 上下文）

---

## 1. 通用约定

### 1.1 响应信封

所有端点返回统一的 `ApiResponse<T>` 信封结构（HTTP 状态码恒为 200，业务异常走信封 code 字段）：

```jsonc
{
  "code":    200,       // 业务状态码；200=成功，其他=异常
  "message": "success", // 人类可读消息
  "data":    { ... }    // 业务数据；异常时为 null
}
```

失败时示例：
```json
{ "code": 429, "message": "触发 每分钟限流上限(10)", "data": null }
```

**未认证**例外：当 SecurityContext 为空时，部分端点直接返回 HTTP 401 信封：
```json
{ "code": 401, "message": "未认证", "data": null }
```

### 1.2 scopeId 协议

scopeId 是路径参数 `{scopeId}`，标识用户提问所属的"页面作用域"：

| scopeId | 含义 | 示例 |
|---|---|---|
| 全局聚合页 | 无实体主键 | `statistics`, `home` |
| 实体绑定页 | `页面[:实体主键]` | `cost_averaging:600519`, `t_calculator:000001` |

前端构造方法：
```typescript
// scopeId 常量表（与前端 COPILOT_SCOPES 共享）
export const COPILOT_SCOPES = {
  HOME: 'home',
  STATISTICS: 'statistics',
  COST_AVERAGING: 'cost_averaging',
  T_CALCULATOR: 't_calculator',
  // ... 其他页面
};

export function composeScopeId(pageSlug: string, entityKey?: string): string {
  return entityKey ? `${pageSlug}:${entityKey}` : pageSlug;
}
```

### 1.3 clientMessageId 幂等键

每个提问请求由前端用 ULID 生成唯一 `clientMessageId`。后端以此做两段式去重：
- **命中且已有回复** → 直接回放已归档回答（不调 LLM）
- **命中且无回复 / 超时未完成** → 续跑或拒绝重复提交

前端每次点击发送前需生成新 ULID，不可重用。

---

## 2. 端点详情

### 2.1 POST `/threads/{scopeId}/messages` — 发送提问

发送一个问题给 AI 助手并获取回复。

#### 路径参数

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `scopeId` | string | ✅ | 页面作用域（如 `statistics`, `cost_averaging:600519`） |

#### 请求体（AskRequest）

```json
{
  "question": "最近做T的胜率怎么样？",
  "sessionTitle": "数据统计",
  "clientMessageId": "01J9ZK3T8QmXpNvBkLgRsYdC7E",
  "contextSummary": "{\"capturedAt\":1756713600,\"truncated\":false,\"_units\":{\"pnl\":\"CNY\",\"winRate\":\"0-1小数\"},\"data\":{\"pnl\":1234.56,\"winRate\":0.62}}",
  "contextOverview": "{\"pnl\":1234.56,\"winRate\":0.62}",
  "timeAnchor": "{\"asOf\":1756713600,\"range\":\"7d\"}"
}
```

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `question` | string | ✅ | 用户自然语言问题 |
| `sessionTitle` | string | ❌ | 会话标题，用于创建/更新会话展示名 |
| `clientMessageId` | string | ✅ | ULID，幂等键。**每次提问必须不同** |
| `contextSummary` | string | ❌ | JSON 字符串。完整页面快照明细（ephemeral），不落库仅内存组装 Prompt |
| `contextOverview` | string | ❌ | JSON 字符串。核心标量指标摘要（JSON 值 <255 字符），写入 ai_chat_message.context_overview 列 |
| `timeAnchor` | string | ❌ | JSON 字符串。时间截面标记（如 `{"asOf":epochSec,"range":"7d"}`），写入 ai_chat_message.time_anchor 列 |

**约束：**
- `contextOverview` JSON 序列化后不能超过 255 字符，否则返回 413
- `question` 不能为空

#### 响应体（AskResponse）

成功时（`code=200`）：
```json
{
  "code": 200,
  "message": "success",
  "data": {
    "userMessageId": 1234,
    "assistantMessageId": 1235,
    "content": "根据你提供的数据，最近做T的胜率为 62%...",
    "promptTokens": 852,
    "completionTokens": 418,
    "channel": "deepseek",
    "userContextOverview": "{\"pnl\":1234.56,\"winRate\":0.62}",
    "userTimeAnchor": "{\"asOf\":1756713600,\"range\":\"7d\"}",
    "ctime": 1756713601
  }
}
```

| 字段 | 类型 | 说明 |
|---|---|---|
| `userMessageId` | Long | 用户问题的 DB ID |
| `assistantMessageId` | Long | AI 回复的 DB ID |
| `content` | String | AI 纯文本回答 |
| `promptTokens` | Integer | 输入 token 数 |
| `completionTokens` | Integer | 输出 token 数 |
| `channel` | String | 使用的模型渠道（当前固定 `"deepseek"`） |
| `userContextOverview` | String | 回显的 contextOverview（落库内容） |
| `userTimeAnchor` | String | 回显的 timeAnchor（落库内容） |
| `ctime` | Long | 回复创建时间（epoch 秒） |

#### 错误码

| code | 条件 | 说明 |
|---|---|---|
| 400 | question 为空 | 问题内容不能为空 |
| 401 | 未登录 | 未认证 |
| 409 | 同 clientMessageId 在 60s 窗内 pending | 上一次提问仍在处理中，请稍后再试 |
| 413 | contextOverview > 255 字符 | 摘要内容超过上限 255 字符 |
| 429 | 触发限流 | 详见 §3 限流规则 |
| 503 | AI 服务异常 | AI 服务未配置 / LLM 调用失败 / 上游报错 |
| 500 | 服务器内部错误 | 未知异常兜底 |

#### 业务流程说明

```
① 前置校验：question 非空 + contextOverview 长度 ≤ 255
② 限流检查：同一 userId 分钟/天双窗口计数
③ 幂等门控：
   ├─ 查 findActiveByClientMessageId(cid)
   ├─ 命中：
   │   ├─ pending 且在 60s 窗内 → 409（拒绝并发重入）
   │   └─ pending 超窗 或 failed → 续跑旧 session
   └─ 未命中 → 新建流程
④ 新建流程：
   ├─ A. REQUIRES_NEW 事务保存 userMsg(status='pending') → 立即 commit
   ├─ B. 懒清理（活跃记录 > 200 条时软删最旧）
   ├─ C. 组装 Prompt（系统指令 + 历史 ok 消息 + 当前问题）
   ├─ D. === LLM 调用在事务外 === （不占 DB 连接）
   ├─ E. success → REQUIRES_NEW 事务存 assistantMsg + 更新 userMsg→'ok'
   └─ F. fail  → REQUIRES_NEW 事务更新 userMsg→'failed'（供重试识别）
```

---

### 2.2 GET `/threads/{scopeId}/messages` — 获取聊天消息

获取指定作用域的聊天记录，支持 keyset 翻页。

#### 路径参数

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `scopeId` | string | ✅ | 页面作用域 |

#### 查询参数

| 参数 | 类型 | 必填 | 默认值 | 说明 |
|---|---|---|---|---|
| `before` | Long | ❌ | null | keyset 游标：只返回 id < before 的记录 |
| `limit` | int | ❌ | 20 | 每页条数（首屏限制 6 条取最近 N 条） |

#### 响应体（ThreadPageResponse）

首次拉取（不传 before）：
```json
{
  "code": 200,
  "message": "success",
  "data": {
    "sessionId": 12,
    "scopeId": "statistics",
    "title": "数据统计",
    "messages": [
      {
        "id": 1230,
        "role": "user",
        "content": "帮我分析一下最近的交易表现",
        "contextOverview": "{\"pnl\":-234.50,\"winRate\":0.45}",
        "timeAnchor": "{\"asOf\":1756710000,\"range\":\"30d\"}",
        "clientMessageId": "01J9ZK3T8QmXpNvBkLgRsYdC7E",
        "ctime": 1756710000
      },
      {
        "id": 1231,
        "role": "assistant",
        "content": "好的，让我来分析一下您的交易数据...",
        "contextOverview": null,
        "timeAnchor": null,
        "clientMessageId": null,
        "ctime": 1756710001
      }
    ],
    "hasMore": true,
    "oldestId": 1230
  }
}
```

翻到底部（无更多）：
```json
{
  "code": 200,
  "message": "success",
  "data": {
    "sessionId": 12,
    "scopeId": "statistics",
    "title": "数据统计",
    "messages": [
      {
        "id": 1198,
        "role": "assistant",
        "content": "...继续之前的分析...",
        "contextOverview": null,
        "timeAnchor": null,
        "clientMessageId": null,
        "ctime": 1756709500
      }
    ],
    "hasMore": false,
    "oldestId": 1198
  }
}
```

**无此 scope 的会话**（返回空容器）：
```json
{
  "code": 200,
  "message": "success",
  "data": {
    "sessionId": 0,
    "scopeId": "sandbox",
    "title": "",
    "messages": [],
    "hasMore": false,
    "oldestId": null
  }
}
```

| 字段 | 类型 | 说明 |
|---|---|---|
| `sessionId` | Long | 关联的 ai_chat_session.id |
| `scopeId` | String | 作用域 |
| `title` | String | 会话标题 |
| `messages` | MessageItem[] | 消息列表（正序，按 id ASC） |
| `hasMore` | Boolean | 是否还有更早的消息（msgs.size() >= limit） |
| `oldestId` | Long | 本页最后一条消息的 id（传给下一次 before 参数） |

#### MessageItem 字段

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | Long | 数据库自增 ID |
| `role` | String | `"user"` 或 `"assistant"` |
| `content` | String | 消息文本（纯文本） |
| `contextOverview` | String? | 仅 user 消息有值；Assistant 消息为 null |
| `timeAnchor` | String? | 仅 user 消息有值；Assistant 消息为 null |
| `clientMessageId` | String? | 仅 user 消息有值 |
| `ctime` | Long | 创建时间（epoch 秒） |

#### 排序规则

- **首屏**（不传 before）：`findFirst6` → 按 id DESC 取最近 6 条 → 反转为正序
- **翻页**（传 before）：原生 SQL `WHERE id < :before AND deleted_at = 0 ORDER BY id DESC LIMIT :limit` → 正序返回

#### 错误码

| code | 条件 | 说明 |
|---|---|---|
| 401 | 未登录 | 未认证 |
| 500 | 服务器内部错误 | 未知异常兜底 |

---

### 2.3 DELETE `/threads/{scopeId}` — 级联清理会话

软删除指定作用域下的所有 Copilot 会话和消息记录。

#### 路径参数

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `scopeId` | string | ✅ | 页面作用域 |

#### 响应体

始终返回 200：
```json
{
  "code": 200,
  "message": "success",
  "data": null
}
```

> **幂等性**：即使该 scopeId 没有活跃会话也返回 200（no-op）。

#### 执行逻辑

```sql
-- 1. 查找活跃 session
SELECT * FROM ai_chat_session WHERE user_id=? AND scope_id=? AND deleted_at=0
-- 2. 若有匹配，标记删除
UPDATE ai_chat_session SET deleted_at=? WHERE id=?
-- 3. 级联标记其下所有消息
UPDATE ai_chat_message SET deleted_at=? WHERE session_id=? AND deleted_at=0
```

- `deleted_at` 设为 epoch 秒数
- **不清除 content/contextOverview/timeAnchor** — 保留排障追溯能力
- 软删除后同 `(user_id, scope_id)` 可复用索引创建新会话

#### 触发源

前端在以下场景自动调用此接口：
1. **手动清空对话** — 用户在 UI 上点击「清空」按钮
2. **业务实体被删除** — 如持仓记录被删除、做T记录被清空、全局重置

#### 错误码

| code | 条件 | 说明 |
|---|---|---|
| 401 | 未登录 | 未认证 |
| 500 | 服务器内部错误 | 未知异常兜底 |

---

## 3. 限流规则

使用 Redis / Spring CacheManager 双窗口限流，键前缀 `rl:{userId}:`：

| 窗口 | 上限 | 过期策略 |
|---|---|---|
| 每分钟 | 10 次 | 按 Unix 时间戳 / 60000 分桶 |
| 每天 | 100 次 | 按 Unix 时间戳 / 86400000 分桶 |

超限返回 `code=429`：
```json
{
  "code": 429,
  "message": "触发 每分钟限流上限(10)"
}
```

缓存不可用时 fail-open（放行 + 警告日志）。

---

## 4. 数据库表结构

### 4.1 ai_chat_session

```sql
CREATE TABLE ai_chat_session (
    id              BIGSERIAL PRIMARY KEY,
    user_id         VARCHAR(64)  NOT NULL,
    scope_id        VARCHAR(100) NOT NULL,
    title           VARCHAR(100) NOT NULL,
    last_message_at BIGINT,
    ctime           BIGINT       NOT NULL,
    deleted_at      BIGINT       DEFAULT 0
);
CREATE UNIQUE INDEX uq_ai_chat_session_user_scope
    ON ai_chat_session(user_id, scope_id) WHERE deleted_at = 0;
```

### 4.2 ai_chat_message

```sql
CREATE TABLE ai_chat_message (
    id                BIGSERIAL PRIMARY KEY,
    session_id        BIGINT       NOT NULL,
    role              VARCHAR(10)  NOT NULL,
    content           TEXT         NOT NULL,
    client_message_id VARCHAR(40),
    status            VARCHAR(20)  DEFAULT 'ok',
    context_overview  VARCHAR(255),
    time_anchor       VARCHAR(100),
    channel           VARCHAR(30),
    model             VARCHAR(50),
    prompt_tokens     INTEGER,
    completion_tokens INTEGER,
    ctime             BIGINT       NOT NULL,
    deleted_at        BIGINT       DEFAULT 0
);
CREATE UNIQUE INDEX uq_ai_chat_message_client_id
    ON ai_chat_message(client_message_id) WHERE client_message_id IS NOT NULL AND deleted_at = 0;
CREATE INDEX idx_ai_chat_message_session_id
    ON ai_chat_message(session_id, id DESC) WHERE deleted_at = 0;
```

### 4.3 status 状态机

| 值 | 含义 | 流转 |
|---|---|---|
| `ok` | 正常完成 | `pending` → `ok`（LLM 成功归档后） |
| `pending` | 正在处理 | `ok` → `pending`（续跑重挂互斥）、`ok` → `pending`（new 初始态） |
| `failed` | 处理失败 | `pending` → `failed`（LLM 调用异常后） |

---

## 5. 错误速查表

| code | HTTP | 场景 | 前端建议操作 |
|---|---|---|---|
| 200 | 200 | 成功 | 正常渲染 |
| 400 | 200 | 参数校验失败 | Toast 提示错误信息 |
| 401 | 200* | 未认证 | 跳转登录 |
| 409 | 200 | 同一 cid 在 60s 窗内 pending | 禁用发送按钮，显示「AI 正在思考...」 |
| 413 | 200 | contextOverview 过长 | 引导缩小时间范围或筛选粒度 |
| 429 | 200 | 触达限流 | 禁用发送，提示冷却时间 |
| 500 | 200 | 服务器异常 | 显示「出错了」+ 重试 |
| 503 | 200 | AI 服务未配置 / 调用失败 | 显示「AI 暂时不可用，稍后再试」 |

> \* 401 在实际 Controller 中仍以 HTTP 200 + `code=401` 信封返回（除未认证拦截器特殊处理外）。

---

## 6. 技术备注

### 6.1 事务边界

```
ask() 主流程                    saveUserMessageInTxn()         persistAssistantResponse()
──────────                      ──────────────────────        ────────────────────────
  ├── 限流                        └── REQUIRES_NEW               └── REQUIRES_NEW
  ├── 幂等门控                                          ─── LLM call (outside txn)
  └── startNewFlow():
      ├── resolveSession()      ✅ isolated txn (REQUIRES_NEW bean)
      ├── saveUserMessageInTxn()✅ isolated txn (REQUIRES_NEW)
      ├── softDeleteOverflow()  same as main
      ├── buildPrompt()         in-memory only
      ├── callLlm()             outside any txn ← 不占 DB 连接
      ├── markUserMessageFailed()? └── REQUIRES_NEW (LLM 异常时)
      └── persistAssistantResponse() └── REQUIRES_NEW
```

关键设计决策：
- **userMsg.save + LLM.call 分离** — userMsg 先 commit，LLM 在事务外执行，避免长调用占用数据库连接
- **会话创建独立 Bean (`AiChatSessionStore`)** — REQUIRES_NEW 防止 DataIntegrityViolationException 污染主事务
- **失败回滚轻量** — 仅更新 userMsg.status→'failed'，不撤销 userMsg 本身（保留排障痕迹）

### 6.2 架构依赖

| 层 | 组件 | 来源 |
|---|---|---|
| Controller | CopilotController | copilot.controller |
| Service | AiChatOrchestrationService | copilot.service |
| Store | AiChatSessionStore | copilot.service.store（REQUIRES_NEW 隔离） |
| Repository | AiChatSessionRepository / AiChatMessageRepository | copilot.repository |
| Rate Limiter | AiChatRateLimiter | copilot.util（Redis 固定双窗口，决策 C7，fail-open） |
| LLM Client | OpenAiChatModel | copilot.config.DeepSeekConfig（@Bean("deepSeekChatModel")，copilot 问答专用付费渠道，不进 llm 责任链） |
| Properties | DeepSeekProperties | copilot.config（copilot.llm.deepseek.*） |

### 6.3 配置项

```yaml
copilot:
  llm:
    deepseek:
      enabled: true
      base-url: "${DEEPSEEK_BASE_URL:}"   # DeepSeek API 地址
      api-key: "${DEEPSEEK_API_KEY:}"      # DeepSeek API Key
      model: "deepseek-chat"              # 模型名称
      read-timeout: PT60S                 # 读取超时（毫秒）

app:
  copilot:
    enabled: true                         # 功能总开关
```

### 6.4 前端集成要点

1. **ULID 生成**：每条用户消息发送前生成唯一 `clientMessageId`，项目已有 `ulid` npm 依赖
2. **分页逻辑**：
   - 首次加载不传 `before` → 服务端取最近 6 条并反转
   - 上拉加载更多时传上一页 `oldestId` 作为 `before` → 服务端取 id < before 的前 limit 条
   - `hasMore=false` 时表示已到达最早消息
3. **发送防抖**：
   - 前端维护 `sending` 状态锁，锁定期间禁发
   - 后端 `409 ASK_IN_PROGRESS` 作为第二道防线（应对弱网断线重连）
4. **失败重试**：
   - 同 `clientMessageId` 重新 POST → 幂等重试（不重复调 LLM）
   - 需保证用户能看到原始消息内容和「重发」按钮
5. **级联清理时机**：
   - 业务实体删除事件 → 前端监听到后调用 DELETE `/threads/{scopeId}`
   - 也可通过确认弹窗触发手动清空
