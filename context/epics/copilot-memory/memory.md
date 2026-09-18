---
dev-loop: memory
format: v1
epic: copilot-memory
total-merged: 2
last-merge: 2026-09-18
---

# copilot-memory：copilot 记忆固化与用户画像抽取

> 目标：为 copilot 域增加「对话 → 长期记忆 → 用户画像」蒸馏层，跨会话记忆注入。
> 设计 SSOT：docs/copilot/memory-profile.md（评审稿，决策记录 22 条）。

关键约束速记（细节以设计文档为准）：

- 记忆跟随窗口：条目归属 session_id，UNIQUE(session_id, topic) 窗口内唯一，跨窗口不去重（窗口即用户自分类主题）
- 记忆提炼=MQ 延迟处理：每轮发轻量种子（仅 userId+sessionId，TTL 60s）→ tick 中转过在途锁 CAS + 重算差量（空 drop 挡种子风暴）→ data worker 差量提炼 → result 回 main 入库清锁；不打断当前聊天；阈值 8 废除
- 用户画像=变化驱动：ΔCount（updated_at > 游标）≥3 或高价值类型变动 → 发布画像任务 → data worker 全量重抽 → result 回 main；游标仅推进至快照 max(updated_at) 绝不 now()（防竞态漏统计）；四字段（性格/底层偏好/禁忌/回复偏好）禁 PII；六类记录类型
- 画像输入治理：per-topic Top-M(4) + topic 内位次衰减 weight + relative_distance 标签，payload 恒定有界；黑名单压制幻觉特征；记忆 <3 条时 personality/deepPreferences 强制空（稀疏护栏）
- 注入固定预算 ≈6.1k 字符（画像+当前窗口记忆+近3天历史）；冷启动空注入懒积累；机制参照 pull-loop-unification.md §3 延迟队列

## 实现现状（2026-09-17，编译 + main 383/data 79 测试全绿，契约与拓扑已验）

- **已落地**：contract（6 key/3 queue + 5 payload + RuntimeHints）· postgres/schema.sql（两表 + ai_chat_session 水位/在途锁列，幂等 ALTER，本地 PG 已应用）· main（CopilotMemoryService：种子发布/tick 闸门 CAS/差量组装/结果入库 upsert+水位+ΔCount 触发/画像入库+游标 GREATEST；CopilotMemoryRecallService 注入；CopilotMemoryController §八五端点；AiChatOrchestrationService 落库后 publishSeed + buildPrompt 注入；TaskPublisher.dispatchDelayedTask）· data（MemoryExtractWorker/MemoryProfileWorker：LlmGateway 复用 datasvc.llm，Map 安全解析剥围栏 + 2 次解析重试，无重试环自愈语义；MqTopologyConfig 3 队列 3 绑定）· 配置双侧 application.yml（copilot.memory.* / 无新增 data 配置）
- **payload 关键口径**：Task/Result 恒携带 userId+sessionId（data 零 DB，关联靠 payload——信封无上下文位，「从信封取」不可行）；snapshotMaxUpdatedAt 统一 epoch 毫秒 long（对齐 envelope occurredAt 惯例）；result.MemoryEntry.recordTypes 仅作高价值触发信号不落库（决策 #14）；contract 契约纯 POJO 零 Jackson 注解（两端同类序列化，注解冗余）；画像稀疏护栏 main 侧确定性兜底（profileSparseGuardMin=3，按 DB 实数 wipe，与 worker prompt 护栏双保险）
- **种子信封 type 陷阱（防复发）**：DLX 到期只改 routing key 不改 body——dispatchDelayedTask(routingKey, envelopeType, ...) 两参分离，信封 type 必须写改写后目标类型（result.memory.extract.tick），写 delay key 自身会被消费端 default 分支静默丢弃
- **ingest 端口宿主（[SSOT 修正] 2026-09-17）**：CopilotMemoryIngestApi 在 **crawler 基包**（AnnouncementIngestApi 先例：消费方定义端口、业务域实现，依赖单向 copilot→crawler）；放 copilot 基包会成 Modulith 环（ModulithVerifyTest 实证拦截）
- **环境注意（宿主机跑测试）**：.env 的 POSTGRES_URL/RABBIT_HOST 是 docker 内部主机名，宿主机须覆盖为 localhost；m2 里 contract 快照会过期，测试前 `./mvnw install -pl stock-calculator-contract` 刷新；沙箱内跑测试必挂（Mockito MockMaker agent attach 被拦 + 回环网络被拦），须非沙箱运行
- **live broker 队列参数漂移**：存量队列与代码声明不一致会 406 挡住全部拓扑声明（RabbitAdmin 全量初始化）——实证 task.history.sync.q 缺 x-single-active-consumer，经用户确认删除后按新参数重建；新增队列靠 data 侧测试上下文声明落地；已实测：3 个 memory 队列+绑定按新参数生效（delay.q classic + DLX→stockcalc.results + tick key），task.history.sync.q 重建带 single-active-consumer，data 套件复跑 79 绿
- **LLM 输出截断护栏（冒烟修复）**：UnexpectedEndOfInput 腰斩根因＝LlmGateway 请求体未显式传 max_tokens、CF 网关隐式缺省偏小——修复＝LlmGatewayProperties.maxTokens 默认 4096（仅放宽上限不改自然停止）+ 两 worker 解析失败输出 rawTail(200 字符尾段) 定位；对账验证通过：水位 8→20、锁清、新条目「关注领域」落库、画像未触发属正确（ΔCount 未达阈值滚存）
- **冒烟观察环境**：data 侧 memory worker 需 DATASVC_WORKER_ENABLED=true 才挂载（缺省 off、队列积压 0 消费者）；IDE JVM 代理参数（proxyHost）会致 LLM 调用失败、移除后直连正常；IDE 控制台日志不可文件化——观察走 MQ 管理 API + `docker exec psql`

## 断点

- [断点] 下一步：冒烟收尾两项待验——①合批（60s 内连发多轮数种子/提炼比）；②注入效果（新会话提问历史话题看回答带画像/记忆）；通过后文档联动（docs/copilot/api.md 新端点、design.md/implementation.md 增补、memory-profile.md 转 active，经用户确认执行）
