# 自定义统计（AI 生成代码）· 后端接口文档 v1.0

> 版本：v1.0（2026-09-07）
> 范围：自定义统计功能对后端的**全部接口需求**——设计原则是最大化复用现有 Copilot 通道，后端改动收敛为「请求可选字段 + 系统提示词模板」，**无新端点、无新表**
> 关联：`docs/custom-stats-backend-support.md`（信息支持文档：动作契约详解、字段字典、模板草稿）；前端仓 `docs/custom-stats-spec.md`（需求）
> 状态：设计定稿，待 P0 开发启动

---

## 1. 设计原则与总览

| 原则 | 说明 |
|---|---|
| 复用优先 | 生成链路走既有 `POST /api/copilot/threads/{scopeId}/messages`（SSE 流式）；鉴权、信封、幂等、限流、会话/消息持久化、容灾路由全部沿用现状 |
| 最小变更 | 后端仅两处改动：① ask 请求体新增可选字段 `taskType`；② 新增一条系统提示词模板（走既有 CopilotPromptTemplate 管理体系） |
| 无新存储 | 生成的 code/prompt/lastResult 全部存前端 Dexie（custom_stats 表）；服务端仍只落聊天消息（actions 数组现状即不落库，本功能不改变该语义） |
| 职责边界 | 后端负责「把契约文档喂给 LLM 并稳定产出合法 action JSON」；前端负责守卫、沙箱执行、结果渲染与留存。后端不解析、不校验、不存储生成代码 |

## 2. 接口变更

### 2.1 POST /api/copilot/threads/{scopeId}/messages（既有端点，请求体扩展）

新增可选字段 taskType：

```jsonc
{
  "content": "帮我统计各股做T净收益排行，画柱状图",   // 既有字段，不变
  "clientMessageId": "01J...",                     // 既有字段，不变
  "contextSummary": { },                           // 既有字段，不变（本场景仅含契约样例，见 §3.1）
  "contextOverview": { },                          // 既有字段，不变
  "timeAnchor": { },                               // 既有字段，不变
  "taskType": "custom_stat"                        // ★ 新增，可选；缺省 = 现行为完全不变
}
```

| taskType 值 | 系统提示词模板 | 用途 |
|---|---|---|
| 缺省 | 现有聊天模板 | 现有问答行为，零影响 |
| `custom_stat` | 新模板 `copilot_custom_stat_gen`（§2.3） | 生成/迭代自定义统计代码 |

路由规则：taskType 仅决定系统提示词模板的选择与输出契约（§2.2），**不改变**任何存储、限流、SSE 事件、容灾逻辑。

### 2.2 响应契约：assistant 消息新增动作类型

SSE 事件结构、消息落库行为完全不变。唯一新增：LLM 输出的 actions 数组中会出现新动作类型 `run_custom_stat`（前端已按 auto 级登记，后端无需处理其语义，透传即可）：

```jsonc
{
  "type": "run_custom_stat",
  "payload": {
    "name": "各股做T收益排行",          // ≤40 字符
    "description": "统计已平仓轮次…",   // 口径说明，≤200 字符
    "prompt": "统计各股做T净收益…",     // ≤2KB 规范化需求种子
    "code": "(ctx) => { … }"          // ≤16KB 完整箭头函数表达式 (ctx) => CustomStatsResult
  }
}
```

校验责任在前端（形状守卫 + 沙箱执行 + 夹具预跑），后端不校验 payload 内容；但提示词模板必须强约束 LLM 输出该 JSON 结构（模板草稿见支持文档 §4）。

### 2.3 提示词模板登记

- 模板 key：`copilot_custom_stat_gen`
- 登记方式走既有 CopilotPromptTemplate 体系（admin 接口 + history）
- 模板内容 = 固定部分（输出契约 + 输出纪律）+ 可变部分（执行契约代码块 + 字段字典）——全文见 `custom-stats-backend-support.md` §4
- 模板属 DB 数据非代码，native-image 无 AOT 影响

## 3. 数据流约定

### 3.1 请求上下文的构成（按场景）

| 场景 | contextSummary 内容 | taskType |
|---|---|---|
| 首次生成 | `sampleRows`：各数据集合 2~3 行真实形状样例（前端 buildFullContext 顺带产出） | custom_stat |
| 草稿迭代 | 同上 + `draftContext`：`{ prompt, code, feedback }`（迭代协议，见支持文档 §5） | custom_stat |
| 重新生成 | 同首次生成；需求种子在 content 文本中（复用存储 prompt + 用户追加要求） | custom_stat |
| 普通聊天 | 现有页面快照（不变） | 缺省 |

约定：自定义统计场景的 contextSummary 新增 `sampleRows` / `draftContext` 两个 key 登记进现有白名单与体积护栏（applySizeGuard ≤12KB）；样例行含用户真实数据但**仅进 prompt 不落库不打日志**（与现有 detail 语义一致）。

### 3.2 不落库语义（本功能零新增）

- actions（含 run_custom_stat payload）不落库——现状即如此，前端草稿/定义存 Dexie
- 服务端新增落库字段：无
- 墓碑/级联清理：无关联（自定义统计定义纯端上数据，不参与 scopeId 级联）

## 4. 错误与限流

| 项 | 约定 |
|---|---|
| 错误分码 | 完全复用现有四种（CONTEXT_TOO_LARGE / RATE_LIMIT_EXCEEDED / UPSTREAM_ERROR / SESSION_NOT_FOUND），无新增 |
| 限流 | 复用 AiChatRateLimiter 阈值。生成代码输出 token 显著高于普通聊天，P0 按现状跑，观察 token 用量后再评估是否为 taskType=custom_stat 设独立阈值（预留，P0 不做） |
| contextSummary 超限 | 走既有 CONTEXT_TOO_LARGE(413) 语义；前端负责样例行裁剪，正常不应触发 |
