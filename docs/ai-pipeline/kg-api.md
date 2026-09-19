---
status: draft
updated: 2026-09-19
---

# kg 时间轴查询 API · 前端对接文档（草案）

> 后端契约：docs/ai-pipeline/cls-news-kg.md §13（设计文档）。本文档面向前端对接，
> 含信封/鉴权约定、逐端点字段表与真实样例、交互落点建议与待确认清单。
> 状态 draft：待前端对齐确认后转 active。

## 一、对接前提

- **Base**：与主服务同源（`:18080`），所有端点前缀 `/api/kg`。
- **鉴权**：与 `/api/search/**` 同法——请求头 `Authorization: Bearer <token>`；
  未登录/过期 → **HTTP 401** + 信封体（前端静默降级不弹窗，与资讯搜索一致）。
- **统一信封**：所有响应（含业务错误）均为 `{ code, message, data }`：

| 场景 | HTTP | code | data |
|---|---|---|---|
| 成功 | 200 | 200 | 业务数据（见各端点） |
| 参数错误（如日期格式无效） | 200 | 40001 | null，message 带原因 |
| 实体不存在 | 200 | 404 | null |
| 系统异常 | 200 | 500 | null |

- **数据现状（重要预期）**：历史回填正在进行（最旧 2023-08-30 起 ASC 分批推进，约 1.2 天追平）。
  追平前，timeline 默认态展示的是**已抽取数据的最新几天**（当前为 2023 年 9 月初），
  并非自然今天——这是按事件时间倒序的正确行为，非 bug；回填完成后自然对齐。

## 二、GET /api/kg/timeline · 时间轴卡片流

搜索与默认浏览**共用端点**：不传过滤参数 = 默认态「最近时间轴」；传 `keyword`/`entityId` = 搜索态。

### 请求参数（全部可选，query string）

| 参数 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `keyword` | string | 无 | 关键词；三路命中：事件标题/详情文本 OR 关联实体名 OR 实体别名 |
| `entityId` | long | 无 | 实体精确过滤（点实体 chips 后走这个，比 keyword 更准） |
| `eventType` | string | 无 | 事件类型过滤；一期为 LLM 自由值（实测「其他」等中文），不强制枚举 |
| `from` / `to` | string | 无 | `yyyy-MM-dd` 闭开区间（from 含当日、to 含当日）；仅约束有归一化时间的事件 |
| `page` | int | 0 | 页码，**0 起**，按「日」分页 |
| `pageSize` | int | 5 | 每页天数（一篇汇编稿 = 一日），上限 30 |

### 响应结构

```jsonc
{
  "code": 200, "message": "success",
  "data": {
    "page": 0, "pageSize": 5,
    "totalDays": 3,          // 命中条件的事件日总数（分页控制）
    "hasMore": false,        // 是否还有下一页
    "matchedEntities": [     // 仅 keyword 搜索态返回（top3）；默认态为 []
      { "id": 31, "name": "杭州第19届亚洲运动会", "entityType": "EVENT",
        "anchorType": "CLS_SUBJECT", "mentionCount": 4 }
    ],
    "days": [
      {
        "articleId": 1459561,          // 源汇编稿 id（日头，亦是溯源外键）
        "date": "2023-09-10",          // 日头日期（汇编稿发布日，标题日期可能差一天）
        "articleTitle": "9月10日周日《新闻联播》要闻22条",  // 原标题（自带 x月x日 无年份）
        "eventCount": 16,
        "events": [
          {
            "id": 57,
            "eventDate": "2023-09-10",  // 归一化事件日期；可能为 null
            "eventTimeText": "今天",     // 原文时间表述（eventDate 为 null 时的展示兜底）
            "title": "习近平举行仪式欢迎赞比亚总统访华",
            "detail": null,             // 补充细节，可能为 null
            "eventType": "其他",
            "articleId": 1459561,
            "entities": [               // 事件实体 chips
              { "id": 1, "name": "习近平", "entityType": "PERSON", "anchorType": null },
              { "id": 17, "name": "赞比亚", "entityType": "PLACE", "anchorType": null }
            ]
          }
        ]
      }
    ]
  }
}
```

### 语义注记

- **组序**：日组按组内最新事件时间倒序（回填乱序落库不影响时序）；组内事件最新在前：时间降序、空值沉底、并列按 id 降序（2026-09-19 定案）。
- **`eventDate` 为 null**：时间解析失败的事件，前端用 `eventTimeText` 兜底展示（如「今天」「上月」），归组仍在日头下。
- **`date` 理论上可能为空串**：源站撤稿等弱一致场景（日头缺失但事件仍展示），前端做防御。
- **日期过滤注意**：`from/to` 会排除无归一化时间的事件（约少量 timeText-only 事件），属预期行为。
- **实体类型枚举**：`entityType` ∈ STOCK/SUBJECT/ORG/PERSON/PLACE/POLICY/EVENT/OTHER；
  `anchorType` ∈ STOCK / CLS_SUBJECT / null（null = 自由实体，未锚定字典）。

## 三、GET /api/kg/entities/suggest · 实体检索建议

搜索框输入补全。`keyword` 按 **实体名或别名** 命中（`mentionCount` 倒序，热实体优先）。

| 参数 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `keyword` | string | 无 | 建议 **≥1 字符且防抖 ~300ms** 再发；空白返回空数组（不算错误） |
| `limit` | int | 10 | 上限 30 |

```jsonc
{ "code": 200, "message": "success", "data": [
    { "id": 31, "name": "杭州第19届亚洲运动会", "entityType": "EVENT", "anchorType": "CLS_SUBJECT", "mentionCount": 4 }
] }
```

别名命中示例：搜「杭州亚运会」能带出规范名「杭州第19届亚洲运动会」（aliases 包含简称）。

## 四、GET /api/kg/entities/hot · 实体热榜

时间轴**空态**「实体热榜 chips」取数：全局提及次数倒序，结构与 suggest 相同。

| 参数 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `limit` | int | 20 | 上限 50 |

空态页面加载时调一次即可；点击热榜 chip = 以该实体 `id` 走 `entityId` 搜索。

## 五、GET /api/kg/entities/{id} · 实体详情摘要卡

搜索态置顶摘要卡 / 实体详情。不存在 → 信封 `code=404`。

```jsonc
{ "code": 200, "message": "success", "data": {
    "id": 31,
    "name": "杭州第19届亚洲运动会",
    "entityType": "EVENT",
    "anchorType": "CLS_SUBJECT",
    "anchorId": "9213",                       // 锚点业务键（subject_id / stock_id，可跳字典详情）
    "aliases": ["杭州亚运会", "第19届亚运会", "第19届亚洲运动会"],
    "mentionCount": 4,                        // 提及次数（文章口径）
    "eventCount": 4,                          // 参与事件数
    "firstSeenAt": "2023-09-08",
    "lastSeenAt": "2023-09-15",
    "relatedEntities": [                      // 高频共现实体 top8（漫游 chips）
      { "id": 1, "name": "习近平", "entityType": "PERSON", "anchorType": null, "coMentionCount": 2 }
    ]
} }
```

## 六、前端交互落点建议（按定案的展示形态）

| 区块 | 取数 | 交互 |
|---|---|---|
| 空态（默认） | `timeline`（无参）+ `entities/hot` | 最近时间轴 + 热榜 chips；明确**不展示补录进度**（已拍板） |
| 搜索框 | `entities/suggest`（防抖） | 选中建议 → `entityId` 搜索；直接回车 → `keyword` 搜索 |
| 搜索态置顶卡 | `matchedEntities`（timeline 自带）→ 点开拉 `entities/{id}` | 展示别名/锚点/共现 chips；`relatedEntities` chip 点击 → 以该实体 id 重新搜索（漫游） |
| 时间轴卡片流 | `timeline`（page 递增续拉） | 卡片命中词高亮（前端对 title/detail 做即可）；`eventDate` 缺失用 `eventTimeText` |
| 卡片详情抽屉 | 无新端点 | `detail` 全文 + `articleId` 溯源外链（跳资讯详情） |

## 七、待前端确认清单

1. **pageSize 默认 5 天**是否合适（每篇 20~30 条事件，一屏约百卡；可改请求参数不动后端）。
2. **热榜默认 20 条**是否合适。
3. **eventType 筛选器**：一期值不受控（LLM 自由文本），若要做筛选，前端可从已拉取数据聚合去重，或后端补一个类型枚举端点——待定。
4. **溯源跳转**：`articleId` 是否能直接路由到现有资讯详情页（NewsSearch 侧）？
5. **高亮实现**：前端本地高亮（当前方案）还是需要后端返回命中片段偏移（暂无此设计）。
6. **锚点跳转**：`anchorType=STOCK/CLS_SUBJECT` 时 `anchorId` 是否要联动自选股/题材详情。

---

*对齐确认后：更新本文档 status → active，并把定案同步回 cls-news-kg.md §13。*
