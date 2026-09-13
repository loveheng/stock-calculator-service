# 数据拉取常态任务自循环化设计（已实施，2026-09-13）

> 状态：**已实施**（随 v4 多副本改造同分支交付，实施记录见 §7）。本文档是 data 模块"常态抓取统一为任务消费"的完整设计推演存档。
> 中间环节（被否方案、发现的约束、修正过的判断）与最终结论同等保留——立项评审时它们是论据，实施时它们是踩坑地图。
> 前置依赖：v4 双镜像多副本改造（部署见 docs/data-worker-replica-deploy.md）。
> 决策编号 L1…（Loop 语义），并入 data-service-split-design.md 时可重新编号。
> 2026-09-13 增补 §8：日历型定时任务扩展设计（CALENDAR 模式，已实施，实施记录见 §8.8）——Phase 3 之外的第二类触发形态，决策记 L8…。

## 0. 一句话结论

将 collector 的常态拉取（CLS 电报 8 分钟轮询、公告采集小时轮询）从 `@Scheduled` 本地定时改为 **RabbitMQ TTL+DLX 自循环延迟任务**：data 消费拉取任务后自我续种，循环的"钟"从 Java 进程内搬到 broker 引擎内；main 作为控制面持有配置表与心跳表，经消息下发配置、接收心跳回报并兜底补种。data 保持零 DB（D2），模块内任务消费模式归一。

## 1. 背景与问题

现状（v4 改造后）data 模块并存三种结构模式：

| 模式 | 门控 | 成员 |
|---|---|---|
| cron 定时自拉 | collector（副本恒=1） | ClsPullTask（fixedDelay 8min）、AnnouncementCollectTask（cron 每小时 :05） |
| 任务竞争消费 | worker（副本=N）+ history-sync（挂 collector 门控） | AnnouncementProcessWorker、EmbeddingComputeWorker、HistorySyncWorker |
| control 快照监听 | collector | SubscriptionSnapshotConsumer |

问题（项目所有者判断，2026-09-13）："常态抓取也是消费任务，如果不这样设计 data 模块就变得混乱，结构不统一、不够清晰。"目标：常态拉取与处理任务统一为同一种模式——队列消费。

不可破坏的约束（源自 data-service-split-design.md，推演中三次差点违反，见 §2.3）：

- D2：data 零 DB，不持任何表；
- D4：collector 采集副本恒=1（源站限流，双跑 = 重复抓取 + 封禁）；
- D6：结果上行 at-least-once，幂等摄取在 main；
- 电报数据连续性：main 宕机期间拉取不能停（缓存窗口会滚过导致永久丢电报）。

## 2. 方案演进（中间环节存档）

### 2.1 方案甲：main 调度发布端 + 合并门槛（第一稿，被修正后否决）

思路：钟搬到 main，main 定时发布 `task.cls.pull` 拉取任务，data 作为普通消费者竞争消费；发布端带合并门槛（队列深度 >0 不发）防并发拉取。

评估出的代价：

1. 定时器不消失只搬家（main 新增调度发布器，总账是复杂度转移）；
2. 新增合并门槛机制——cron `fixedDelay` 天然无重叠，任务化后 8min 节奏 × 消费延迟可能被并发消费；
3. ingest webhook 是 HTTP 推送接收端，钉在端口上，collector 概念缩不掉；
4. **数据连续性硬伤（致命，评估后期才发现）**：main 宕机 → 无任务发布 → 拉取停止 → 电报流不停，宕机超过缓存窗口宽度即永久丢电报。

被否原因：代价 4 不可接受（初判"main 挂了不拉更合理"在电报场景是错的——正确行为是拉取继续、结果积压 quorum 队列、main 回来补 ingest）；代价 1/2/3 为纯成本。

### 2.2 方案乙：TTL+DLX 自循环（采纳为终局形态）

机制：`task.<src>.pull.delay.q`（classic、per-message TTL、无消费者、DLX=TASKS 交换机、DLK=对应拉取 routing key）→ 到期死信进 `task.<src>.pull.q`（quorum 业务队列）→ collector 消费：抓取 → 深度守卫 → 续种 → 心跳回报 → 手动 ack。启动种子由双保险提供：data bootstrap（启动时延迟队列空则投）+ main 看门狗（低频兜底）。

评估中修正了原提案的三个点：

1. **SAC 不解决双种子**（原提案以 Single Active Consumer 防多副本并发）：两条种子到期都进工作队列，SAC 只保证串行消费，每次消费都会续种 → 双种子永久循环、频率永久翻倍。正解是**深度守卫**：续种前 `queueDeclarePassive` 拿 messageCount，>0 则跳过续种。守卫在场后所有异常自愈归一：双种子、崩溃在续种后 ack 前（消息重投再拉一次、查深度见种子已在、跳过）、bootstrap 与在途任务竞争。SAC 降级为可选。
2. **ack 顺序 = 拉取 → 续种 → 最后 ack**（手动 ack）：kill -9 后消息重投自动续命，"种子彻底断绝"的真实窗口只剩 broker 级灾难，远小于直觉估计。
3. **机制可行性已有一半实证**：现网 retry 环（task.\*.q.retry）就是 classic + per-queue TTL + DLX，LavinMQ 生产天天在跑。未验证的仅 per-message expiration（见 §5）。

相对方案甲的决定性优势：main 宕机时循环照常——种子沉淀队列、data 消费后照拉、结果积压不丢，数据连续性天然成立。

### 2.3 三个补充点的评估（续期日志 / 心跳表 / 配置表）

| 补充点 | 结论 | 修正 |
|---|---|---|
| 续期结构化日志 | 直接采纳（深度、TTL、续期轨迹），黑盒排障的解药 | 无 |
| 心跳存 main 表 | 采纳 | 初稿写路径**违反 D2**（data 直写库）→ 改为 data 每次续期发 `result.pull.heartbeat` 回报、main 消费写表；且防误清能力来自补种器而非表——表的增量价值 = 可视化 + 历史 |
| 配置存 main 表 | 采纳 | 两处硬伤：① per-queue `x-message-ttl` 声明期不可变（改 = 删队列重建，不一致声明触发 PRECONDITION_FAILED），动态 TTL 必须换 **per-message expiration**（深度守卫保证队列内最多一条种子，per-message 的队头过期限制在此场景无害）；② "worker 读 DB 配置"又违反 D2 → 配置经 `control.pull.config` 快照下发（订阅快照同款覆盖式缓存） |

连带修正记录：

- "把下一轮 TTL 编进种子、省掉 control 消息"被否——自循环的种子是 **data** 发的，data 发种子时就得知道 TTL，配置到达 data 的通道省不掉；
- `enabled=false` 语义 = 跳过续种 = 循环死亡，重启靠 main 看门狗，**停/启传播延迟上限 = 看门狗周期**（写死为设计决策，不是 bug）；
- 心跳与配置挂在同一条续期路径上 → 此前"3a 裸闭环 / 3b 平台化"的两段拆分作废，Phase 3 一次成型（见 §6）。

### 2.4 被否选项速查表

| 被否项 | 否决理由 |
|---|---|
| 方案甲（main 调度发布端） | main 宕机丢电报（数据连续性硬伤）+ 定时器只是搬家 |
| SAC 防多种子 | 只串行不降噪，频率永久翻倍 |
| per-queue TTL 动态修改 | 队列参数声明期不可变 |
| TTL 编进种子省 control 消息 | 种子由 data 发布，配置通道省不掉 |
| data 直读/直写 main 库 | D2 违规 ×2（心跳写路径、配置读路径） |
| data 保留 @Scheduled 不动 | 结构不统一（触发本次讨论的原始痛点）；collector 语义绑死定时器，无法动态调速与远程停启 |

## 3. 终局设计

### 3.1 形态

```
main（控制面，单副本，持两表）
  ├─ crawler_task_config(task_code, enabled, ttl_ms) ──变更即推──► control.pull.config
  ├─ pull_heartbeat(task_code, last_renew_time, next_expected_time, applied_ttl)
  │        ▲                                                    │
  │        └────────────── result.pull.heartbeat ◄──────────────┘
  └─ 看门狗(@Scheduled 低频)：读两表 → 心跳超期且 enabled → 补种种子
                                          enabled=false → 不补种

data（数据面，collector 门控实例，零 DB）
  task.<src>.pull.delay.q（classic，无消费者，per-message TTL）
    └─ TTL 到期 → DLX(TASKS, DLK=task.<src>.pull) → task.<src>.pull.q（quorum）
          └─► collector 消费：抓取 → 深度守卫 → 续种(per-message TTL)
                → 心跳回报 → 手动 ack
```

### 3.2 契约增量

- 队列 x4：`task.cls.pull.q` / `task.cls.pull.delay.q` / `task.announcement.collect.q` / `task.announcement.collect.delay.q`
- 消息 x2：`control.pull.config`（main→data 配置快照，覆盖式缓存）、`result.pull.heartbeat`（data→main 续期回报，含 depth / appliedTTL）
- 表 x2：`crawler_task_config`、`pull_heartbeat`（main 侧，schema 立项时细化）
- 移除：`@EnableScheduling` + 两个 cron 类（ClsPullTask、AnnouncementCollectTask 变身为拉取消费者）

### 3.3 data 循环细则（collector 门控，worker 变体不参与）

1. 消费 `task.<src>.pull.q`（手动 ack、prefetch=1，复用 collectorControlListenerFactory）；
2. 执行拉取——现有 ClsCollectorService / AnnouncementCollectorService 逻辑原样复用（含订阅快照缓存，首帧前空转语义不变）；
3. 深度守卫：passive declare delay 队列，messageCount > 0 → 跳过续种；
4. 续种：发种子到 delay 队列（TASKS 交换机 + delay routing key），per-message TTL 取本地配置缓存（代码内置默认 enabled=true / ttl=8min|1h，control 快照覆盖）；
5. 心跳回报（失败仅落日志，绝不阻塞续种）；
6. 手动 ack（最后一步）。

启动 bootstrap：ApplicationReadyEvent 时 delay 队列 messageCount == 0 → 投种子。

### 3.4 main 看门狗

- 周期 30min（可配），对每个源：心跳表 next_expected_time 超期 且 配置 enabled → 补种；
- enabled=false：不补种（停用语义的执行者）；重新启用后 ≤ 一个周期复活；
- 补种幂等性由 data 深度守卫兜底（补多不炸，最多多拉一轮）。

### 3.5 设计不变量

1. data 零 DB（D2）：一切 main 侧状态经消息进出；
2. 循环内同时最多一条种子（深度守卫 + 手动 ack 顺序保证）；
3. 心跳/日志是观测信号，种子 + 深度守卫是唯一控制信号；
4. worker 变体不含任何拉取消费者（collector 门控不放松，D4 语义保留）；
5. 配置默认值内置在 data 代码里，control 快照只做覆盖（冷启动不死锁）。

## 4. 决策记录

| 编号 | 决策 | 理由 |
|---|---|---|
| L1 | 常态拉取从 @Scheduled 改为 TTL+DLX 自循环任务 | 结构统一（一切皆任务消费）；main 宕机数据连续性 |
| L2 | 深度守卫为循环核心机制，SAC 仅可选 | 双种子自愈归一（SAC 只串行不降噪） |
| L3 | ack 置于续种之后 | 崩溃重投自愈，种子丢失窗口最小化 |
| L4 | 心跳/配置状态归 main，消息进出 | D2；main 成为控制面，data 保持数据面 |
| L5 | 动态 TTL 用 per-message expiration | per-queue TTL 声明期不可变 |
| L6 | enabled=false = 循环死亡 + 看门狗复活 | 停/启语义显式化，传播延迟上限 = 看门狗周期 |
| L7 | Phase 3 一次成型，不拆 3a/3b | 心跳/配置与续期同路径，拆开做两遍 |

## 5. 风险与验证清单（立项先决）

- [ ] LavinMQ per-message expiration 实证（集成测试：无消费者延迟队列 + 逐条 TTL 到期死信）——per-queue 已由 retry 环生产实证，per-message 未验，**立项先决**；
- [ ] 种子 = 贵重状态的运维心智：误 purge 即停摆，恢复靠 bootstrap / 看门狗；排障入口 = `[CLS Loop]` 结构化日志 + 心跳表；
- [ ] 拓扑膨胀：+4 队列 +2 消息类型，两侧声明参数一致性照旧维护（PRECONDITION_FAILED 风险同现有约定）；
- [ ] 节奏语义变化：周期 = TTL + 处理耗时（自适应顺延，源站友好，属改善）；公告采集失去"每小时 :05"整点错峰（cninfo 无整点高峰，评估可接受）；
- [ ] 回归面：ClsPullTask / AnnouncementCollectTask 现有测试迁移 + 深度守卫 / 续种 / bootstrap 单测 + CollectorGateTest 更新。

## 6. 立项条件（立项时点核对）

1. v4 双镜像多副本上线并跑稳——v4 代码已提交推送（ce06e60），部署验收进行中；
2. §5 第一条 per-message TTL 实证通过——**已通过**（见 §7）；
3. ~~立项后本文档并入 data-service-split-design.md~~ → 主文档 §6 已挂交叉引用行，决策重编号并入时再定。

## 7. 实施记录（2026-09-13）

- **立项先决**：`LavinMqPerMessageTtlE2ETest` 通过——种子到达耗时 2035ms（TTL=2000ms），无 expiration 阴性对照不转发，passive declare 深度探针可用；
- **契约**：MqQueue / MqKey / MessageType 新增 pull 系常量；新增 `PullConfigPayload` / `PullHeartbeatPayload`；
- **data**：MqTopologyConfig 四队列（2 delay classic + 2 work quorum 无 DLX）；ClsPullTask / AnnouncementCollectTask 改造为 `ClsPullConsumer` / `AnnouncementCollectConsumer`（finally 续种 → ack，L3）；新增 `PullLoopProperties` / `PullConfigCache` / `PullLoopRenewer` / `PullLoopBootstrap`；`@EnableScheduling` 移除（data 零 Spring 定时器，MqHeartbeatWatchdog 走独立线程池）；
- **main**：`pull_task_config` / `pull_heartbeat` 两表（schema.sql + data.sql 播种，调速/停启事实源）；`PullLoopWatchdogTask`（配置快照重推 + 心跳判活补种 + 进程内补种节流）；`PullHeartbeatRecorder`（事件落表）；`PullHeartbeatEvent` 置于 monitor 基包，crawler 消费端经事件跨域移交（**首版直引 TaskDispatchApi 触发 Modulith 环，依赖倒置为 `PullLoopDispatchPort` + crawler 侧 `PullLoopDispatchAdapter` 消除**）；`TaskPublisher.dispatchSeed`；
- **验证**：data 76 绿 / main 377 绿（TaskServiceTest 例行排除）/ ModulithVerifyTest 过 / LavinMQ TTL 实测过；本地开发库曾为空库导致 main 上下文类假失败，已用仓库 schema.sql + data.sql 幂等初始化（22 表）；
- **未含**：仪表盘 UI（直接读 pull_heartbeat 表即可，data 侧零改动）。

## 8. 日历型定时任务扩展设计（CALENDAR 模式，已实施 2026-09-13）

> 状态：**已实施**（实施记录见 §8.8；首个接入任务 task.hello.world，每日 07:00 Asia/Shanghai）。Phase 3（§3）解决的是高频轮询型常态任务（分钟级 TTL 自循环）；本节补齐第二类触发形态——日历型 cron 任务（"每周一 08:00""每月 1 号"）。两类任务触发机制分治，控制面（配置/心跳两表 + 看门狗）与 data 面契约（任务消费 + 心跳回报）完全复用。决策编号承接 L1…L7，记 L8…。
> 评估输入：外部评审材料（2026-09-13，提出"cron 衰变为动态 TTL 种子"纯 MQ 方案后自行修正为看门狗终态）。本文档采纳其终态方向、否决其初稿机制，理由存档于 §8.2。

### 8.0 一句话结论

日历型任务不走延时自循环、不引入 cron→TTL 衰变种子：`pull_task_config` 增加 CALENDAR 模式（cron 表达式 + 调度游标 next_expected_time），main 看门狗以 CAS 认领逾期槽位后**直接投递一次性任务**到 work 队列；data 侧为无续种、无 delay 队列、无 bootstrap、零配置依赖的一次性消费者。"钟"放在 DB 的调度游标里，而不是 broker 的 TTL 里。

### 8.1 两类触发形态的分域

| | 高频轮询型（§3，已实施） | 日历型（本节） |
|---|---|---|
| 典型任务 | CLS 电报 8min、公告采集 1h | 每周一 08:00、每月 1 号 |
| 钟 | broker（per-message TTL 倒计时） | DB（next_expected_time 游标 + 看门狗比对） |
| 触发方 | data 续种（自循环）+ main 看门狗兜底 | main 看门狗唯一触发 |
| main 宕机影响 | 循环照常（设计初衷，数据连续性） | 恢复后补跑一次（skip-missed） |
| data 侧动作 | 消费 → 抓取 → 续种 → 心跳 → ack | 消费 → 执行 → 心跳 → ack（无续种） |
| broker 在途状态 | delay 队列内的种子 | 无（零在途状态） |
| 下次触发时间 | **不落库**（刻意设计，见下注） | next_expected_time 落库（调度游标，L9） |

约束不变：D2（data 零 DB）、D4（采集单副本）、D6（at-least-once + main 幂等摄取）。

> **库内记账的边界**（2026-09-13 与项目所有者确认）：LOOP 行**不落 next_expected_time**——
> 自循环的实际周期 = TTL + 处理耗时（自适应顺延，L 实测语义见 §5），"下次何时跑"没有确定值，
> 写库只会产生一个会过期的假象。LOOP 行在库里只记两样事实：**配置**（enabled / ttl_ms，控制面）
> 与**执行记录**（pull_heartbeat.last_renew_time），运维健康口径 = last_renew_time 距今的新鲜度
> （超期 → 看门狗补种），而非对下次触发时间的推算。CALENDAR 行则相反：next_expected_time 是
> 调度游标（控制信号），必须落库供看门狗 CAS 认领（L9 / L12）。一句话：**LOOP 钟在 broker、库只
> 记账；CALENDAR 钟和账都在库**。

### 8.2 方案评估存档（被否项）

| 被否项 | 否决理由 |
|---|---|
| cron→TTL 衰变种子（评审材料初稿：main 收 result → 查 cron → 投下一轮 TTL 种子） | ① **方案甲变体复活**：续种点搬回 main 的 result 处理器，main 宕机 → 无续种 → 循环断（§2.1 已否决过同构方案）；② **违反不变量 3**：现网 pull 类任务回上行只有尽力而为的 result.pull.heartbeat，"result.pull.completed" 契约不存在，把观测通道升格为控制信号需重建可靠投递而收益为零；③ **周级种子不可运维**：停用/改 cron 的生效延迟从"看门狗周期"退化为"种子 TTL（最长一周）"，直接推翻 L6 停启语义；误 purge / broker 磁盘损坏 = 无声空窗；④ **per-message TTL 队头过期限制**：长 TTL 种子压住同队列短 TTL 消息，异构 TTL 需逐任务 delay 队列（拓扑膨胀）；且 §7 的 TTL E2E 实证仅覆盖 2s 单种子场景，周级 TTL 未验证 |
| 独立"状态表"存 next_expected_time（评审材料终态形态） | 游标是期望状态（调度器所有权），认领 CAS 要求游标与判逾期条件**同行、单语句原子**；拆表徒增 join 与双写一致性负担 |
| data 感知 schedule_mode（快照带 mode、renewer 按 mode 分支续种） | 模式是任务身份属性而非运行时开关；CALENDAR 任务为新增任务身份，data 零配置依赖更简（L10）；"既有任务翻模式"需求出现时另立决策 |
| 看门狗单方法统一降 60s | 会把现网已验证的 LOOP 判活/快照重推节奏同时 ×30，"自愈更快"的收益不值得动在产路径；拆双节奏零回归（L14） |

### 8.3 终态设计

#### 8.3.1 表结构（pull_task_config 增列，schema.sql 同步 + 存量库幂等 ALTER）

```sql
ALTER TABLE public.pull_task_config
    ADD COLUMN IF NOT EXISTS schedule_mode      varchar(16) DEFAULT 'LOOP' NOT NULL,
    ADD COLUMN IF NOT EXISTS cron_expression    varchar(64) NULL,
    ADD COLUMN IF NOT EXISTS timezone           varchar(64) DEFAULT 'Asia/Shanghai' NOT NULL,
    ADD COLUMN IF NOT EXISTS next_expected_time timestamptz NULL;
```

- `schedule_mode`：LOOP（§3 自循环）/ CALENDAR（本节）；存量行默认 LOOP，行为逐字节不变；
- `next_expected_time`：调度游标，仅 CALENDAR 行使用——期望状态的一部分，看门狗认领即推进（认领 CAS 与判逾期条件同行，单语句原子）；
- `pull_heartbeat` 不动：对 CALENDAR 行是纯观测记录（最近一次执行），不参与调度。

#### 8.3.2 main 看门狗：拆双节奏（在产路径零回归）

`PullLoopWatchdogTask` 拆为两个独立 @Scheduled 方法：

- `watch()`（30min，**现有职责不变**，仅两处增量）：① LOOP 判活循环过滤掉 CALENDAR 行（防误入 DELAY_KEYS / dispatchSeed 路径）；② 配置快照仅含 LOOP 行（CALENDAR 不下发，L10）；
- `calendarClaim()`（60s，新增，`pipeline.pull-loop.calendar-claim-period-ms` 默认 60000）：

```
for row in CALENDAR 且 enabled:
    if row.next_expected_time IS NULL:              # 初始化：建行/清游标后首见
        UPDATE SET next_expected_time = cron.next(now)
        WHERE task_code = ? AND next_expected_time IS NULL
        continue                                    # 初始化不触发执行（crontab 语义）
    if row.next_expected_time > now: continue
    # 认领：CAS 推进游标，affected=1 者独得投递资格（多副本安全，L12）
    affected = UPDATE SET next_expected_time = cron.next(now), updated_at = now()
               WHERE task_code = ? AND enabled AND schedule_mode = 'CALENDAR'
                 AND next_expected_time <= now()
    if affected == 1:
        try dispatchCalendarTask(row.task_code)     # 直发 TASKS，routing key = task_code
        catch: 回滚游标至原逾期值                    # 保持逾期，下轮重认领，槽位不丢
        log 触发轨迹（taskCode / 已消耗槽位 / 下一槽位）
```

- cron 解析用 Spring 自带 `CronExpression`（6 域、无 `?`、零新依赖），`parse(cron).next(now.atZone(zone))`，时区取行内 timezone 列；配置写入与启动双重 fail-fast 校验；
- **skip-missed（L9）**：下一槽位恒从 now 计算，绝不从上次执行时间算——宕机跨多个触发点收敛为"回来后补跑一次"，不追帧爆发。

#### 8.3.3 发布契约增量（极小）

- `PullLoopDispatchPort` 增方法：

```java
/** 日历任务认领投递：直发 TASKS 交换机 routing key = taskCode（无 TTL、无 delay 队列、无信封） */
void dispatchCalendarTask(String taskKey);
```

- `PullLoopDispatchAdapter` 实现：委托 `TaskPublisher`，以 `dispatchSeed` 同款裸消息（无信封、消费端不解析载荷）发布，仅去掉 expiration；Modulith 依赖方向不变（monitor 基包 port ← crawler adapter）；
- **contract 零新增类型**：无新 MessageType、无新 payload 类；接入新任务时仅按需加 MqQueue / MqKey 常量；
- 拓扑：每个日历任务 +1 条 work quorum 队列（无 DLX，两侧声明参数一致）；**无 delay 队列**。

#### 8.3.4 data 一次性消费者形态

```java
@RabbitListener(queues = MqQueue.TASK_X, containerFactory = "collectorControlListenerFactory")
onTask(message, channel, deliveryTag) {
    try {
        doWork();                                            // 业务执行
        reportHeartbeat(taskCode, depth=0, appliedTtlMs=0);  // 观测信号，尽力而为
    } catch (Exception e) {
        log.warn("本轮失败（不重试，等下一日历点，L11）");
    } finally {
        channel.basicAck(deliveryTag, false);                // 手动 ack
    }
}
```

与 `ClsPullConsumer` 的差异仅三点：**无续种、无深度守卫、无 bootstrap**。心跳复用 `result.pull.heartbeat`（`PullHeartbeatRecorder` 照常落表，appliedTtlMs=0 为 CALENDAR 行哨兵值）；门控按任务性质选 collector（采集类，D4 串行——prefetch=1 下积压顺延执行、不并发）或 worker（计算类）。

#### 8.3.5 操作语义对照

| 操作 | LOOP（现状，L6） | CALENDAR（新增） |
|---|---|---|
| 停用 enabled=false | 跳过续种循环死亡；生效 ≤ 看门狗周期 | 看门狗停认领；**无在途种子，即时生效** |
| 重新启用 | ≤ 一周期复活 | 游标已逾期 → 下轮认领立即补跑一次，随后回到日历节奏 |
| 修改 cron | 快照 ≤ 周期生效 | 下一次认领用新表达式；**配套动作：清 next_expected_time 为 NULL**（看门狗按新 cron 重初始化，否则旧游标按旧时间先触发一次） |
| main 宕机跨触发点 | 循环照常（设计初衷） | 恢复后补跑一次（skip-missed，不追帧） |
| 单轮执行失败 | 不 nack，等下轮 TTL | 不重试，等下一日历点（L11） |
| "立即执行" | — | 管理操作：清游标（下轮认领即触发）或手动 dispatch；本期不做 UI |

### 8.4 决策记录

| 编号 | 决策 | 理由 |
|---|---|---|
| L8 | 日历型任务 = 看门狗认领 + 直接投递一次性任务，不引入 cron→TTL 衰变种子 | §8.2 四条否决理由；"钟"放 DB 游标不放 broker TTL |
| L9 | 调度游标 next_expected_time 落 pull_task_config；补跑恒 skip-missed（下一槽位从 now 算） | 与认领 CAS 同行原子；宕机补跑收敛为一次，不爆发追帧 |
| L10 | CALENDAR 配置不下发 data（快照仅 LOOP 行） | data 对日历任务零配置依赖（比 LOOP 更简：只有执行+心跳+ack）；模式是任务身份非运行时开关 |
| L11 | 日历任务单轮失败不重试，等下一日历点 | 与 LOOP"失败等下轮"同构（仅间隔不同）；retry 环为可选后续增强 |
| L12 | 认领协议 = CAS 推进游标先于投递，投递失败回滚游标保持逾期 | 单副本下与"先投递后更新"等价，多副本天然安全（affected=1 者独得资格），零额外组件 |
| L13 | cron 方言 = Spring CronExpression（6 域、无 ?），时区显式列（默认 Asia/Shanghai），写入与启动双校验 | 零新依赖；Quartz 式 `?` 语法不兼容，需 fail-fast；时区不依赖服务器默认值 |
| L14 | 看门狗拆双节奏：watch() 30min 不动（仅过滤 CALENDAR + 快照剔除），calendarClaim() 60s 新增 | 在产 LOOP 路径零回归；60s 为日历触发精度的成本上限（全表 <10 行，扫描可忽略） |

### 8.5 设计不变量（增量）

6. 日历任务的唯一控制信号 = config 表游标 + 看门狗认领；broker 侧零在途状态（无种子、无 delay 队列）；
7. CALENDAR 与 LOOP 在看门狗内互斥分流，CALENDAR 行永不进入 DELAY_KEYS / dispatchSeed 路径；
8. 心跳仍是观测信号（不变量 3 延续）；CALENDAR 行 appliedTtlMs=0 为哨兵值，仪表盘口径 = 最近一次执行时间。

### 8.6 风险与验证清单（立项先决）

- [x] 单测：CronExpression 解析与 next 计算 + 非法表达式 fail-fast（边界语义依赖 Spring 库，daily 07:00 断言覆盖）；
- [x] 单测：认领协议（NULL 初始化不投递 / 逾期认领 / affected=0 跳过 / 投递失败回滚 / 未到期与停用跳过）；
- [ ] 集成：认领 → dispatch → 一次性消费 → 心跳落表 跨服务 E2E（待新镜像部署后由真实 07:00 触发实证）；
- [x] 回归：watch() 过滤 CALENDAR 行后，LOOP 判活 / 快照重推行为不变（既有 3 场景零改动通过）；
- [x] Modulith：dispatchCalendarTask 经 port 走 crawler adapter，依赖方向不破（ModulithVerifyTest 过）；
- [ ] 心跳表 CALENDAR 行的仪表盘口径（appliedTtlMs=0 哨兵）评审；
- [ ] 运维心智：日历任务的"钟"在 DB——误 UPDATE 游标即误触发 / 漏触发，游标变更需走 SQL 评审（对应 LOOP 的"种子贵重"心智）。

### 8.7 新增日历任务接入清单（runbook）

1. **contract**：MqKey / MqQueue 增常量（命名照 `task.<src>.pull` 风格；无 delay 队列常量）；
2. **拓扑**：两侧 MqTopologyConfig 声明该 work quorum 队列（无 DLX；无 delay 队列）；
3. **data**：一次性消费者（§8.3.4 形态；门控按任务性质选 collector / worker；无 renewer / bootstrap）；
4. **main**：`pull_task_config` 播种行（schedule_mode='CALENDAR'、cron_expression 六域、timezone）；
5. **自动接管**：看门狗 ≤ 60s 补齐游标（NULL → 下一日历点），自首个触发点起自动调度；
6. **回归**：ModulithVerifyTest + 全量测试（TaskServiceTest 例行排除）。

### 8.8 实施记录（2026-09-13）

- **契约**：MqKey / MqQueue 新增 `task.hello.world` / `task.hello.world.q`（无 delay 队列常量，contract 零新增消息类型）；
- **main**：`pull_task_config` 四列（schema.sql CREATE 带列 + 存量库幂等 ALTER）；`PullTaskConfigEntity` 增四字段 + MODE_LOOP / MODE_CALENDAR 常量；`PullTaskConfigRepository` 增 `findByScheduleMode` 与认领三语句（init / claim / rollback，各自独立短事务不跨 MQ 投递持锁，显式写 updatedAt——批量 UPDATE 绕过 @UpdateTimestamp）；`PullLoopWatchdogTask` 拆双节奏：watch() 过滤 CALENDAR 行 + 快照仅含 LOOP 行（在产行为不变），新增 `calendarClaim()`（60s，`pipeline.pull-loop.calendar-claim-period-ms`）与 `@PostConstruct` cron fail-fast 校验；`PullLoopDispatchPort.dispatchCalendarTask` + adapter + `TaskPublisher`（种子同款裸消息去掉 expiration）；
- **data**：MqTopologyConfig 增 `task.hello.world.q`（pullWorkQueue 形态，quorum 无 DLX）+ TASKS 绑定；新增 `hello/HelloWorldConsumer`（一次性消费：执行 → 心跳（appliedTtlMs=0 哨兵，不变量 8）→ 恒 ack，无续种 / 深度守卫 / bootstrap，L10 对配置零依赖）；
- **播种**：data.sql 增 `task.hello.world` 行（CALENDAR / 0 0 7 * * * / Asia/Shanghai，ttl_ms 哨兵 0）；
- **验证**：main 383 绿（含 ModulithVerifyTest）/ data 79 绿（TaskServiceTest 例行排除）；单测新增 PullLoopWatchdogTaskTest 6 场景（§8.6 认领协议全覆盖）+ HelloWorldConsumerTest 3 场景；本地开发库以仓库 schema.sql + data.sql 幂等升级（LOOP×2 回填默认值，hello.world 就绪）；
- **环境注意（宿主机跑测试）**：.env 的 POSTGRES_URL / RABBIT_HOST 写的是 docker 网络内部主机名，宿主机测试须覆盖为 localhost；`ResultPublisherTopologyTest` 断言 result.ingest.q 队列深度，live main 容器消费会吃掉测试消息——跑 data 集成套件前先停 app 容器；
- **首个触发点**：新 main 启动后看门狗 ≤60s 初始化游标为次日 07:00（crontab 语义不立即执行），此后每日 07:00 Asia/Shanghai data 侧打印 Hello World；容器需以新镜像重建后生效。
