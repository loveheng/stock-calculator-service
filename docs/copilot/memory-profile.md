---
status: draft
updated: 2026-09-17
---

# Copilot 记忆固化与用户画像抽取设计

> 状态：设计评审稿。本篇为 copilot 域独立主题文档，域总体设计见 [design.md](design.md)；实施后本文转 active 并同步 implementation 记录。

## 一、背景与目标

copilot 域已具备 AI 聊天主链路（DeepSeek 接入、会话/消息持久化、SSE 流式问答）。本设计为对话增加「记忆 → 画像」蒸馏层，设计思路借鉴分层记忆模型（工作记忆 / 情景记忆 / 语义记忆 / 固化 / 遗忘）：

1. 对话消息流水沉淀为**长期记忆条目**（按主题归并、冲突改写、可溯源）；
2. 从长期记忆抽取**用户画像**——性格 / 底层偏好 / 禁忌 / 回复偏好四类行为特征，事实源为**用户所问的问题**（助手回复仅作上下文消解，见 §六）；
3. 画像与长期记忆在后续对话中自动注入，使 AI 聊天具备跨会话记忆能力；
4. **不记录称呼、姓名等任何个人信息**（隐私护栏见 §九）。

分层口径：**记忆跟随会话窗口产生**——情景流水与固化都按窗口随水位逐段推进，长期记忆条目随每个窗口的固化以 upsert 改写充实（内容跨窗口滚动累积，沉淀用户的选择与权衡）；**用户画像 = 对跨窗口累积后的长期记忆的聚合判定**（全量重抽，非单窗口结论）。

## 二、现状与约束

- **触发机制（评审改版）**：**MQ 当定时器**——记忆提炼=延迟队列（per-message TTL + DLX，到期经 tick 中转异步提炼，不打断当前聊天）；画像抽取=**变化驱动**（ΔCount 阈值 + 高价值类型变动即时触发，无变化零调用）。延迟队列机制参照 `docs/architecture/pull-loop-unification.md` §3。
- **执行分工**：data worker 消费任务（LLM 网关提炼/重抽，零 DB，输入由 payload 携带）；main 负责发布、result 消费入库、召回注入——与 announcement 管线同构。
- **Spring AI 2.0.1**（OpenAI 兼容）；实现期按「标准实现优先」原则以本地依赖源码实证 API 签名，不凭记忆编码。
- **native 构建**：新增契约 payload 类需在 `ContractRuntimeHints` 注册反射提示。

## 三、总体架构

```mermaid
flowchart TD
    subgraph 提炼链 · 每轮延迟 + tick 防风暴
        A["main · 对话消息落库"] --> B["main · 发布轻量种子<br/>仅 userId + sessionId"]
        B --> C["task.memory.extract.delay.q<br/>TTL 默认 60s · 到期 DLX 改写"]
        C --> T["result.memory.extract.tick<br/>main 重算差量 · 空 drop"]
        T --> D["task.memory.extract.q<br/>data worker · LLM 差量提炼"]
        D --> E["result.memory.extracted<br/>main 入库 upsert + 水位推进"]
    end
    subgraph 画像链 · 变化驱动
        F["result 入库时统计 ΔCount<br/>≥3 或高价值类型变动"] --> G["main 组装加权 payload<br/>per-topic Top-M + weight 衰减"]
        G --> H["task.memory.profile.q<br/>data worker · LLM 全量重抽"]
        H --> I["result.memory.profile<br/>main 入库 version+1 + 游标推进"]
    end
    E -.->|ΔCount 统计| F
    I --> J["main · 对话注入 · 固定预算<br/>画像 + 当前窗口记忆 + 近 3 天历史"]
```

与 pull-loop 同构的分工：main 控制面（发布 / 入库 / 看门狗 / 召回），data 数据面（零 DB worker），契约模块承载 task / result；配置（TTL、cron、开关）走既有配置数据表。

## 四、数据模型（postgres/schema.sql）

**copilot_memory（长期记忆条目）**

| 字段 | 类型 | 说明 |
|---|---|---|
| id | BIGSERIAL PK | 自增 |
| user_id | VARCHAR(64) NOT NULL | 用户ID（对齐 ai_chat_session.user_id 现行口径——auth 主键 UUID 的文本形态；user_custom_stat 同款修正先例） |
| session_id | BIGINT NOT NULL | 所属会话窗口（**记忆跟随窗口**，跨窗口不去重） |
| topic | VARCHAR(64) NOT NULL | 主题键，**固定枚举池取值**（见 §六，非 LLM 自由文本） |
| content | VARCHAR(2000) NOT NULL | 蒸馏后的记忆结论（同维度多结论合并陈述，≤300 字） |
| source_message_ids | JSONB | 溯源：来源 ai_chat_message.id 数组（仅用户提问），保留最近 20 条防膨胀 |
| status | VARCHAR(10) DEFAULT 'active' | active / archived（遗忘软删） |
| pinned | BOOLEAN DEFAULT false | 置顶记忆：注入时全量携带（见 §七） |
| ctime | BIGINT NOT NULL | 原始时间戳（秒），排序用 |
| created_at / updated_at | TIMESTAMPTZ | 自动时间戳 |

- 唯一约束 `UNIQUE (session_id, topic)`——**窗口内** SSOT 锚点：同窗口同主题恒唯一，提炼即改写，严禁并存。**跨窗口不做去重**（窗口即用户自分类主题，跨窗口语境由画像与近期历史承担）。topic 取自固定枚举池（§六），防 LLM 同义变体绕过约束。

**copilot_user_profile（用户画像 + 固化状态，一用户一行，懒创建 upsert）**

| 字段 | 类型 | 说明 |
|---|---|---|
| user_id | VARCHAR(64) PK | 业务主键，手动写入（同 copilot_memory.user_id 口径） |
| profile | JSONB | 见下方结构 |
| profile_version | INT DEFAULT 0 | 每次全量重抽 +1 |
| blacklisted_features | JSONB DEFAULT '[]' | 画像黑名单：用户手动移除的特征，后续抽取严禁再输出 |
| last_profile_extracted_at | TIMESTAMPTZ NULL | 画像变化驱动游标：ΔCount = active 且 updated_at > 此值的条数；**仅推进至任务快照的 max(updated_at)，绝不用 now()**（防竞态漏统计） |
| created_at / updated_at | TIMESTAMPTZ | 自动时间戳 |

```json
{ "personality": [], "deepPreferences": [], "taboos": [], "responsePreferences": [] }
```

**提炼水位与在途锁（每窗口一组，失败自愈）**：

- `ai_chat_session` 新增两列：`last_memory_extracted_message_id BIGINT DEFAULT 0`（水位）、`memory_extract_dispatched_at TIMESTAMPTZ NULL`（在途锁，NULL=无在途）；
- 每轮提炼差量 = 该窗口内 id > 水位的消息；result 成功推进水位并**清锁**（置 NULL），失败不动——下一轮自动把漏掉的消息并入差量补漏；
- **tick 到达的发布闸门（单语句 CAS，多副本安全）**：`UPDATE ai_chat_session SET memory_extract_dispatched_at = now() WHERE id = ? AND (memory_extract_dispatched_at IS NULL OR memory_extract_dispatched_at < now() - 超时)`，affected = 1 才发布提炼任务（在途 LLM 执行窗口内到达的 tick 直接丢弃）；超时兜底（默认 30s，可配）防在途任务丢失后死锁；
- 计数由查询推导，不缓存。

## 五、执行模型（延迟提炼 + 变化驱动画像，MQ 当定时器）

> 机制参照 `docs/architecture/pull-loop-unification.md` §3：延迟队列（per-message TTL + DLX）做「聊天沉淀窗」——**MQ 即定时器，零新增调度设施**。

**提炼链（每轮一轻量种子，tick 中转防风暴）**：

1. assistant 回复落库（AFTER_COMMIT）→ main 发布轻量种子 `task.memory.extract.delay`（payload 仅 {userId, sessionId}，per-message TTL 默认 60s，取自配置）——聊天请求线程只做一次发布，SSE 零感知；
2. TTL 到期经 DLX 改写路由到 `result.memory.extract.tick`（RESULTS 交换机，main 消费）——**tick 中转是防风暴的闸门（双重拦截）**：main 先过**在途锁 CAS**（同会话已有提炼在途且未超时 → 丢弃 tick），再重算差量（会话水位后的对话片段），**差量为空直接丢弃**；通过双重拦截才组装 payload（差量片段 + 当前窗口记忆快照）发布 `task.memory.extract` → data `MemoryExtractWorker`（LLM 网关）提炼；
3. `result.memory.extracted` → main result 消费：窗口记忆 upsert + 推进水位 + **清在途锁**。

   （评审修正存档：初版「TTL 窗口天然合并连发」表述有误——种子差量在发布时定格，TTL 到期不会重新合并，实际是差量重叠的冗余提炼；tick 中转重算差量后才实现真合批：60s 内连发 10 轮仅 1 次 LLM 调用，其余种子被水位拦截丢弃。）

**画像链（变化驱动，无变化零调用）**：

1. 提炼结果入库时统计 **ΔCount** = 该用户 active 记忆中 `updated_at > last_profile_extracted_at` 的条数（由查询推导，不缓存）；ΔCount ≥ 阈值（默认 3，可配）**或涉及高价值类型**（禁忌 / 回复偏好）变动 → main 发布 `task.memory.profile`；否则不发布（ΔCount 累计滚存至下次触发）；
2. payload 组装（**画像输入治理，防 Token 爆炸**）：per-topic 按 ctime 倒序取 **Top-M**（默认 4 条），条目权重按 topic 内相对位次线性衰减 `weight = max(0, 1 − 0.25 × 位次差)`（最新 1.0），并附 `relative_distance` 标签（如「最新（当前窗口）」）；payload 同时携带 `snapshotMaxUpdatedAt`（组装时该用户全部 active 条目的 max(updated_at)）与 `blacklistedFeatures`（画像黑名单）；payload 总上限 ≈ 7 topics × 4 条 × 300 字，**恒定有界**；
3. data `MemoryProfileWorker` 全量重抽四字段（带黑名单与稀疏护栏，见 §六）→ `result.memory.profile` → main 入库 version+1，游标**仅推进至 payload 的 snapshotMaxUpdatedAt**（GREATEST 原子推进，绝不 now()——防任务执行期间新变化被漏统计）。

**幂等 / 失败自愈**：`(session_id, topic)` 窗口内唯一 upsert + 会话提炼水位（result 成功才推进）+ 在途锁（CAS 置位、result 成功清锁，超时兜底）；tick 重复到达由在途锁拦截；画像游标仅在 result 成功后推进至快照 `snapshotMaxUpdatedAt`；均不影响对话主链路。

**范围**：contract 新增 6 个 key / 3 条队列（extract 的 delay+work、profile 的 work；tick 与两类 result 复用既有 result.ingest.q 的 `result.#` 绑定——独立 tick 队列会与该绑定重复投递，拓扑禁止）+ 5 个 payload（Tick 轻量 / ExtractTask / ExtractedResult / ProfileTask / ProfileResult，含 `ContractRuntimeHints` 注册）；data 2 个 worker + 拓扑声明；main 发布端 / tick 消费 / result 消费 / 召回 / controller；配置（TTL、ΔCount 阈值、Top-M、衰减系数）走 application.yml。

## 六、固化与抽取规则（data worker 执行：提炼延迟差量 + 画像定时聚合）

**第①步 记忆提炼（延迟差量，每轮一种子）**

- 输入：任务 payload 携带的差量（会话水位后的对话片段，user + assistant 成对）+ 当前窗口记忆快照；输出：窗口记忆条目 upsert（经 result 回 main 入库）；
- 事实源口径：**仅提炼与用户自身偏好、习惯、要求相关的结论**；助手回复只用于理解代词与省略语（如「就用你说的第二个方案」），不得把 AI 的建议内容当作用户偏好记录；
- 记录内容规范（「记录什么」，六类，高价值信号优先——选择与权衡是画像判定的主要证据）：

| 记录类型 | 记录什么 | 示例 | 主要喂给画像字段 |
|---|---|---|---|
| 选择记录 | 用户在 AI 给出的选项/方案中最终选定的项（含被弃选项） | 缓存选型选 Caffeine、弃 Redis | deepPreferences / personality（决策风格） |
| 权衡记录 | 做选择时给出的理由、约束、排除项、在意点 | 「不想引外部依赖，宁可功能少」 | deepPreferences（在意点） |
| 禁忌记录 | 明确的底线与排斥项 | 「绝不用某类方案」 | taboos |
| 回复偏好记录 | 对回答方式的显式要求与修改请求 | 「说重点」「别用表格」 | responsePreferences |
| 习惯与偏好记录 | 稳定的操作习惯、审美与风格倾向 | 「习惯先谈风险再谈收益」 | deepPreferences / personality |
| 目标与阶段记录 | 当前目标与经验阶段（行为语境，非身份信息） | 「当前主要做短线」 | personality / deepPreferences |

- 条目内容约定：单条 ≤300 字、同主题多记录合并陈述；含权衡的条目按「结论 + 权衡（理由/弃选项）」组织；选择类结论归入其所属领域主题；
- 记录类型是**提炼口径（prompt 层）**，不新增 DB 列——类型在 content 中自明，避免 topic × type 双分类轴漂移；后续若注入/展示需要类型过滤再演进加列；
- Topic 枚举池（硬约束）：只能从固定池取值——`[风险偏好, 交易与操作习惯, 关注领域, 决策风格, 沟通偏好, 信息渠道, 杂项]`（初版池，评审可调）；不属于既有一律归「杂项」。main 入库时二次校验，非枚举值归入「杂项」（防 prompt 漂移的双保险）；
- 冲突改写：与现有条目**矛盾时输出改写版本（同 topic 覆盖）**；单条 ≤ 300 字（同维度多结论合并陈述）；
- 截断容错声明（prompt 固定尾注）：传入消息可能因超长被截断（尾部带「[已截断]」标记），基于已有部分尽力推导，勿纠结语法完整性。

**第②步 画像抽取（变化驱动，跨窗口聚合）**

- 输入：payload 携带的按 topic 折叠后的加权记忆条目（Top-M + weight 衰减 + relative_distance 标签，组装规则见 §五）；全量重抽语义不变——非增量修补，防漂移且遗忘后画像可自愈；
- 输出：§四 JSON 结构四类特征数组（responsePreferences 的证据 = 用户对回答风格的显式要求与修改请求，直接用于指导后续回答）；
- 硬约束：仅从用户提问行为归纳；**严禁输出姓名、称呼、联系方式、住址、证件等任何个人信息**；证据不足输出空数组；每项一句话；
- **黑名单压制**：payload 携带的 `blacklistedFeatures` 中任何特征**严禁再输出**（用户手动移除即终态，防幻觉特征复发）；
- **稀疏冷启动护栏**：全局记忆条目不足 3 条时，`personality` 与 `deepPreferences` **强制返回空数组**——仅允许提取**显式**的 `taboos` 与 `responsePreferences`（显式陈述可信，推断结论需证据量）；
- **冲突消解与权重规则**：同 topic 出现矛盾或演进结论时，**强制以 weight 最高的最新记忆为准**；低 weight 旧记录仅作背景参考，与新记录冲突直接忽略，不得写入最终画像。

**提炼节奏**：每轮一轻量种子（TTL 默认 60s 合批窗）；tick 中转重算差量，空则丢弃。画像触发：变化驱动——ΔCount ≥ 3 或禁忌 / 回复偏好变动即时触发（决策 #18）。

## 七、召回注入（main，copilot 域内）

- 挂载点：`AiChatOrchestrationService` 构建 prompt 阶段调用新增的 `CopilotMemoryRecallService`，读取画像、记忆与近期历史拼装注入段，模板走 `CopilotPromptResolver` 既有提示词模板体系。

**注入构成与固定成本预算（每次聊天恒定，与记忆积累量无关）**：

| 段 | 内容 | 预算（可配） | 填充策略 |
|---|---|---|---|
| 防虚构声明 | 固定前缀 | ~100 字 | 恒定 |
| 画像段 | 四类特征全量 | ≤800 字 | 超预算按类均衡截到条目边界 |
| 置顶记忆 | 当前窗口 pinned 条目 | ≤1200 字 | 全量携带，超预算按 ctime 新→旧截到边界 |
| 窗口记忆 | 当前窗口非 pinned，ctime 倒序 top-N（N=20） | ≤2000 字 | 按预算填充，截到条目边界 |
| 近期历史 | 最近 3 天对话（user + assistant，时间正序，**排除当前会话**——当前会话由 ChatMemory 承载） | ≤2000 字 | 超预算保留最新，截到消息边界 |

- 总注入封顶 ≈ 6100 字符（各段预算和），**每轮聊天成本恒定有界**，不随记忆/历史增长而膨胀（token 折算实现期实测校准）；配置键集中 main `application.yml`（`copilot.memory.recall-*` 前缀：profile-budget / pinned-budget / memory-budget / recent-budget / recent-days / top-n）；
- 宁短勿溢：任何段超预算一律在**条目/消息边界**截断，严禁截半个条目；
- 置顶机制在窗口内保留核心记忆防时间盲区（§十 #10）；pgvector 语义召回演进方向不变；
- 跨窗口语境由画像段 + 近期历史承担，**其他窗口的记忆不注入**（窗口化口径，见 §十 #15）；
- 防虚构声明：注入段固定前缀「以下为你确实记得的该用户特征与近期对话，仅可基于此回应，未提及的不得编造」。

**冷启动初始化流程（无数据 → 慢慢积累）**：

1. 用户无画像行 / 无记忆 / 无近期历史 → 注入段整体省略，额外成本 ≈ 0；
2. 画像行在首次定时画像任务或首次手动重抽时懒创建 upsert（§四），无预建数据；
3. 积累路径：用户消息 ≥ 8 → MQ 固化 → 记忆条目生成 → 画像 v1 抽取 → 下一轮起注入逐步生效——初始化不需要任何特殊引导或预填。

实现注意：近期历史查询按 user_id + 时间范围扫 `ai_chat_message`，实现期核对该表现有索引是否覆盖，不足则补（避免每轮对话全表扫）。

## 八、对外接口（CopilotMemoryController，端点名定稿随实施）

| 端点 | 方法 | 说明 |
|---|---|---|
| /api/copilot/profile | GET | 查看当前画像（含 profile_version） |
| /api/copilot/profile | PATCH | 人工修正画像：body {field, feature}，移除即写入黑名单并 version+1（防 LLM 幻觉死锁，不触发重抽——改动即终态） |
| /api/copilot/memory | GET | 查看本人长期记忆条目 |
| /api/copilot/memory/{id} | DELETE | 遗忘：条目置 archived + 立即发布一次画像任务（防残留，不等日历点） |
| /api/copilot/memory/{id}/pin | PATCH | 置顶 / 取消置顶（body: {pinned}），置顶上限 10 条 |
| /api/copilot/profile/extract | POST | 手动发布一次画像任务（立即执行语义，同 pull-loop §8.3.5 管理操作） |

- userId 一律取自会话鉴权（与 `CopilotController` 现行口径一致，实现期核实），不收客户端传参。

## 九、隐私与护栏

- **禁 PII 双保险**：抽取 prompt 硬约束（§六）+ 画像 schema 本身不含任何身份字段（§四）；
- **遗忘链路**：删除/归档记忆 → 全量重抽画像，被删内容随之从画像中蒸发；
- **失败隔离**：固化全链路异步，任何失败不影响 SSE 对话主链路；重试与死信由既有 retry 环 / dead.q 基建兜底；
- **用户隔离**：读写一律以 user_id 为界，严禁跨用户访问。

## 十、决策记录

| # | 决策点 | 结论 | 理由 | 被否备选 |
|---|---|---|---|---|
| 1 | 触发方式 | 记忆=延迟队列种子（TTL 合批窗）；画像=CALENDAR 行 + 看门狗认领 | 复用项目「MQ 当定时器」机制（pull-loop-unification），提炼彻底脱离聊天线程 | 进程内 @Async（改版废除：仍占主服务资源，可能干扰聊天） |
| 2 | 执行位置 | data worker（LLM 网关） | 重活下沉既有分工；data 零 DB，输入由 payload 携带 | main 进程内调 LLM（改版废除） |
| 3 | 入库位置 | main 消费 result.* 统一入库 | data 零 DB 铁律（pull-loop D2 不变量） | data 直写 DB |
| 4 | 事实源 | 对话片段成对下发（user + assistant）；用户提问为事实源，助手回复仅作代词消解 | 纯提问存在指代缺失（「用你说的第二个方案」无法解析）；payload 增量可控（各截断 2000 字符 + 截断标记） | 仅发用户提问（评审指出的严重缺陷，已修正） |
| 5 | 幂等机制 | (session_id, topic) 窗口内唯一 upsert + 会话提炼水位（成功才推进，失败下轮补漏）+ 在途锁 CAS（发布前单语句置位，见 #20） | 重复触发无副作用；失败自愈无需重试环；在途窗口防重复派发 | 全局 (user_id, topic) 去重（改版废除） |
| 6 | 画像更新 | 变化驱动触发全量重抽（ΔCount 阈值 + 高价值类型即时）+ version 递增 | 无变化零 LLM 调用；游标由查询推导不缓存 | CALENDAR 定时重抽（改版废除）；增量修补字段 |
| 7 | 画像字段 | 性格 / 底层偏好 / 禁忌 / 回复偏好，禁 PII | 产品定位；回复偏好直接指导回答风格 | 称呼等个人信息（明确排除） |
| 8 | 提炼节奏 | 每轮一轻量种子 + tick 中转重算差量（空 drop）；批量阈值 8 维持废除 | 水位闸门挡种子风暴：连发 10 轮仅 1 次 LLM 调用 | 发布时定格差量（评审指出的冗余提炼缺陷，已修正） |
| 9 | Topic 生成 | 固定枚举池 + 杂项兜底；main 入库二次校验归一 | 防 LLM 同义变体绕过唯一约束导致平行膨胀（SSOT 破产）；代价是容量收敛为每维度一条合并结论 | LLM 自由生成 topic |
| 10 | 记忆召回 | 注入当前窗口记忆（pinned 全量 + ctime 补足）+ 画像/近期历史承担跨窗口语境；v2 可演进 pgvector 语义召回 | 窗口化后其他窗口记忆不注入，语境靠画像聚合 | 跨窗口 top-N 注入 |
| 11 | 超长截断 | 字符截断 ≤2000 + 「[已截断]」尾标 + prompt 容错声明 | 信封 JSON 由 Jackson 转义不会损坏，真实风险是 LLM 误读截断片段；token 级截断需引入 tokenizer，v1 不值 | Token 维度截断 |
| 12 | 注入成本 | 分段固定预算 + 边界截断；冷启动空注入、懒积累 | 每轮聊天成本恒定有界，与记忆/历史增长无关 | 按条数注入无预算上限 |
| 13 | 画像判定证据 | 显式提炼「选择 + 权衡 + 回复偏好」为高价值信号；画像扩为四字段 | 选择比陈述更能暴露真实偏好；权衡记录防把结果当原因；回复偏好直接指导回答行为 | 仅记录结论不记录权衡过程 |
| 14 | 记录内容规范 | 六类记录类型（prompt 级口径）+「结论 + 权衡」条目约定；不新增 record_type 列 | 明确定义「记录什么」；单分类轴（topic）防漂移；schema 保持精简 | 新增 record_type 结构化列（留作演进） |
| 15 | 记忆窗口化 | 条目归属 session_id，窗口内 (session_id, topic) 唯一、跨窗口不去重 | 窗口即用户自分类主题；跨窗口语境由画像（定时聚合）+ 近期历史承担 | 全局 topic 去重跨窗口改写（改版废除） |
| 16 | 定时器机制 | 提炼=延迟队列 TTL + tick 中转（在途锁 CAS + 空 drop 双重拦截）；画像=变化驱动 | 复用 pull-loop 延迟队列机制，MQ 当定时器；无变化零调用 | CALENDAR 定时（改版废除：无变化也空转）；main @Scheduled |
| 17 | 画像输入治理 | per-topic ctime 倒序 Top-M（默认 4）+ topic 内位次线性衰减 weight + relative_distance 标签 | 跨窗口条目随窗口数线性增长，全量下发必击穿 Token 上限（评审高风险项）；权重基准用 topic 内位次而非全局序号差，避免误杀长期未变的真实偏好 | 全量 active 快照下发；全局会话序号差衰减 |
| 18 | 画像触发 | 变化驱动：ΔCount（updated_at > 游标）≥ 阈值或高价值类型变动；游标推进语义见 #19 | 无变化零调用；游标单条 SQL 可审计；手动重抽端点兜底静默风险 | CALENDAR 定时（改版废除） |
| 19 | 画像游标推进 | 仅推进至任务快照 snapshotMaxUpdatedAt（GREATEST 原子推进），绝不用 now() | 任务执行期间的新变化（updated_at < now()）会被 now() 游标永久漏统计（评审盲点一） | 游标推进为当前时间 |
| 20 | 提炼在途锁 | memory_extract_dispatched_at 单语句 CAS 置位 + 超时兜底（默认 30s） | LLM 执行窗口内到达的 tick 重复派发（评审盲点二）；超时防在途任务丢失死锁 | 无在途判定（tick 窗口内重复派发） |
| 21 | 画像人工修正 | PATCH 画像移除特征 + blacklisted_features 黑名单压制后续抽取 | LLM 幻觉特征靠删记忆无法可靠消除（归纳是隐式的），不修正即 UX 死锁（评审盲点三） | 仅靠删记忆触发重抽 |
| 22 | 稀疏冷启动护栏 | 记忆 < 3 条时 personality / deepPreferences 强制空，仅收显式 taboos / responsePreferences | 稀疏输入诱发 LLM 过度归纳，冷启动画像噪声污染语气（评审盲点四）；显式陈述与推断结论可信度不同 | 无稀疏限制 |

## 十一、实施顺序与验证

1. contract：MqKey / MqQueue（extract 的 delay+work、tick、profile 的 work）、MessageType、payload（MemoryExtractTickPayload / MemoryExtractTask / MemoryExtractedResult / MemoryProfileTask 含 snapshotMaxUpdatedAt + blacklistedFeatures / MemoryProfileResult）+ `ContractRuntimeHints` 注册；
2. `postgres/schema.sql`：copilot_memory / copilot_user_profile（含画像游标列 + 黑名单列）两表 DDL + `ai_chat_session` 提炼水位列与在途锁列；
3. main：entity / repository → 发布端（落库后轻量种子）→ tick 消费（在途锁 CAS → 重算差量，空 drop）→ result 消费（窗口记忆 upsert + 水位推进清锁 / ΔCount 统计与画像任务发布 / 画像入库 + 游标推进至快照 max）→ 召回注入 → controller；
4. data：`MemoryExtractWorker` / `MemoryProfileWorker`（LLM 网关）+ 拓扑声明（delay 队列无消费者、work 队列 quorum、tick 与 result 走既有 result.ingest.q 由 main 消费，两侧参数一致）；
5. 配置：application.yml（种子 TTL、在途锁超时、ΔCount 阈值、Top-M、衰减系数、注入预算族）；
6. 验证：无 DB 单测排除口径跑通 → 本地 PG + LavinMQ 起容器跑模块单测 → 冒烟：连发多轮观察 tick 合批（仅 1 次提炼）、查窗口记忆；偏好变动后观察画像任务自动触发；新会话验证注入；
7. 实现期 API 实证：Spring AI 2.0.1、data LLM 网关与 pull-loop 延迟队列 / DLX 配置签名以本地依赖 / 既有代码为准；
8. 收尾文档联动：`docs/copilot/api.md`（新端点）、`design.md` / `implementation.md`（域核心流程增补）。
