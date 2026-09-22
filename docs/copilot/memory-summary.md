---
status: active
updated: 2026-09-22
---

# Copilot 记忆系统速记

## 核心机制

### 记忆提炼 = 延迟队列 + MQ 定时器
- **触发**：每轮聊天发轻量种子（仅 userId+sessionId，TTL 60s）→ tick 中转过在途锁 CAS + 重算差量（空 drop 挡种子风暴）→ data worker 差量提炼 → result 回 main 入库清锁
- **输入治理**：per-topic Top-M(4) + topic 内位次衰减 weight + relative_distance 标签，payload 恒定有界（≈7×4×300字）
- **输出**：窗口记忆条目 upsert，窗口内 (session_id, topic) 唯一，跨窗口不去重

### 用户画像 = 变化驱动 + 稀疏护栏
- **触发**：ΔCount（updated_at > 游标）≥3 或高价值类型（禁忌/回复偏好）变动 → 发布画像任务
- **游标推进**：仅推进至任务快照 snapshotMaxUpdatedAt（GREATEST 原子），绝不 now()（防竞态漏统计）
- **输入治理**：黑名单压制幻觉特征，记忆 <3 条时 personality/deepPreferences 强制空（仅收显式 taboos/responsePreferences）
- **输出**：四字段 JSON（性格/底层偏好/禁忌/回复偏好），全量重抽语义

## 关键设计

### 并发控制
- **在途锁**：memory_extract_dispatched_at 单语句 CAS 置位 + 30s 超时兜底（防 tick 重复派发）
- **水位推进**：result 成功才推进 + 清锁，失败下轮补漏，零重试环
- **幂等保证**：(session_id, topic) 唯一 upsert + 会话水位 + 在途锁 CAS

### 召回注入
- **固定预算**：≈6.1k 字符（画像+窗口记忆+近期历史），每轮成本恒定有界
- **构成**：防虚构声明段 + 画像段 + 置顶记忆 + 窗口记忆 + 近期历史
- **冷启动**：无数据时省略注入，懒积累随记忆增长逐步生效

### 隐私与护栏
- **禁 PII 双保险**：抽取 prompt 硬约束 + schema 不含身份字段
- **失败隔离**：记忆链完全异步，不影响对话主链路
- **遗忘机制**：删除记忆 → 立即发布画像任务，内容从画像蒸发

### 决策亮点
- **MQ 当定时器**：复用 pull-loop 延迟队列机制，零新增调度设施
- **窗口化架构**：记忆跟随 session_id，跨窗口语境靠画像聚合
- **话题枚举池**：7 类硬编码 + 杂项兜底，防同义变体绕过唯一约束
- **高价值触发**：禁忌/回复偏好变动即时触发画像重抽
- **人工修正**：PATCH 画像移除特征 + 黑名单压制，破 LLM 幻觉 UX 死锁