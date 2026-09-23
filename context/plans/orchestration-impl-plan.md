# Orchestration 未实现功能实施规划

> 2026-09-23 · 基于 dev 分支代码实探（非纯文档推演）
> 覆盖 todos 中 orchestration 全部 5 条待办

## 〇、代码实探修正（规划依据）

| # | 原认知 | 实探事实 | 对规划的影响 |
|---|---|---|---|
| 1 | slots 恒空需让 LLM 规划时顺手输出 | `normalizeIntent` 已产出 slots（Planner.java L74-90），fullPlan 落库时丢弃（L199-206） | 修复=传参，成本≈0，**不必等三件套**，提前到 P1 |
| 2 | mq_wait 唤醒键是「队列」 | 唤醒键是 `correlation_id==trace_id`，任何 `task.completed.*` 都能唤醒在等实例（TaskResultEventListener L48-53） | 领域事件化是**匹配模型升级**而非补字段；现有 listener 可保留兼容 |
| 3 | draft→candidate「自动冒烟」缺触发 | 全仓**无任何写 candidate 的代码路径**，verify 校验「仅 candidate 可上架」= draft 永远停在 draft | P1-5 必须补完整闭环：冒烟执行 + 终态升格 |
| 4 | plan 状态枚举无 rejected | 仅 draft/candidate/verified/deprecated（PlanEntity L58-61） | P2 需加枚举值 + 全链路状态流转 |
| 5 | depends_on 已声明未消费 | Planner prompt 要求输出（L170），Executor 纯数组顺序 for 循环（L92-123） | P5 拓扑调度是补消费端；LLM 产出端已就绪 |
| 6 | 存量 plan 池状态 | 近空（verified 池刚起步） | P4 的 embedding 重算/迁移零成本上车，窗口就是现在 |

## 一、分期与顺序

```
P1 复用链路地基（打包 5 项小活）→ P2 HITL 拒绝反哺 → P3 领域事件化 → P4 Planner 三件套 → P5 Executor 并行化
```

排序理由：
- P1 全是小改动、互相独立、立即收益（复用质量 + HITL 池健康度）；
- P2 的「终态补记」挂点与 P1-2 同位，顺手做；
- P3 独立性强，可与 P2 并行推进（不同人/不同会话）；
- P4 的 prompt 改造建议与 P1-1 合并成**一次 prompt 调整**，但向量锚切换牵连面大放后；
- P5 改动面最大（Executor 核心循环重写），且 2① 异动归因 DAG 前置依赖它，最后做。

---

## P1 复用链路细节补齐（5 项小活，预计 1~1.5 天）

### P1-1 slots 传递修复（原①）
- **落点**：`stock-calculator-orchestration/.../planner/Planner.java` L179-206
- **做法**：`fullPlan` 增加入参，把 `normalizeIntent` 产出的 `slots` 写入 `param_schema`；LLM 降级路径（L83-89 空 slots）保持现状兜底。
- **验收**：新落库 plan 的 `param_schema.slots` 非空；复用命中后 `fillParams` 的 required 校验生效。

### P1-2 use_count 口径改终态补记（原②）
- **落点**：`PlanRepository.java`（加 SQL：`UPDATE plan SET use_count=use_count+1, last_used_at=CURRENT_TIMESTAMP WHERE id=:planId`，现有 `updateUseStats` 可复用）；`TaskRunnerListener.java` L63-74（终态回调处，**仅 done 时**按 `instance.plan_id` 调用）；删除 Planner.java L61 命中即 +1。
- **注意**：一个 plan 可被多实例复用，每实例 done 补记一次即可，无需去重。

### P1-3 match_log 匹配日志落表（原③）
- **落点**：`orchestration-schema.sql`（新表）+ `PlanRepository`（insert）+ `Planner.matchVerified` L94-117（两处写入）。
- **表结构建议**：`id, query_text, query_vector(vector(1024)), top_hits(jsonb: [{plan_id,distance,adopted}]), adopted(boolean), fallback_reason, created_at`。
- **写入点**：命中采纳时写一条（adopted=true）；走 `continue` 回退重规划时写一条（adopted=false + reason: distance/needs_review/semantic_check）。
- **验收**：pgvector 阈值调参可按 `fallback_reason` 分布回溯。

### P1-4 draft 孪生去重（原④）
- **落点**：`PlanRepository`（新查询：`searchDraftNear`——status IN ('draft','candidate') 向量近邻，复用 `searchVerified` 的 SQL 模式）；`Planner.fullPlan` L186 落库前调用。
- **语义**：近邻距离 < 阈值（暂复用 plan-match.max-distance）→ **更新原条目**（intent_text/intent_domains/param_schema/plan_dag 刷新 + updated_at），新 DAG 覆盖旧；否则新建。
- **验收**：同一意图换措辞问 3 次，待审核清单只出现 1 条且内容最新。

### P1-5 draft→candidate 自动冒烟闭环（原⑤，D10 第一环）
- **落点**：`Planner.fullPlan`（draft 落库后发 `task.orchestration.run`）；`TaskRunnerListener`（识别冒烟上下文，done → plan 升 candidate，failed → 保持 draft + needs_review=true）。
- **设计点（唯一需要拍板的）**：冒烟执行如何标识？建议 plan_dag 顶层加 `smoke_run: true` 标记或 task params 带 `purpose:"smoke"`；冒烟实例与普通实例共用 TaskRunner/Executor 链路，**可叠 dry_run**（工具 mock/降级执行，机制现状待确认——若 dry_run 未实现，P1-5 先用真实小参数跑，限制在低风险工具白名单内）。
- **验收**：draft 落库后 5 分钟内自动出现 candidate（冒烟通过）；HITL 待审核清单同时可见 draft（失败）与 candidate（通过）。

---

## P2 HITL 拒绝状态机补档 + 负样本反哺（小-中，预计 1 天）

- **落点**：`PlanEntity.java` L58-61（枚举加 `rejected`）；`HitlReviewService.java` L126-163；`Planner.matchVerified` L94-117（前置负样本规避查询）。
- **做法**：
  1. `rejectTask` 联动：实例置 ST_FAILED（现状保留）+ 实例 `plan_id` 对应 plan 置 `rejected`（终态）+ reviewer_note 落 plan（现 note 只进日志，顺带修）。
  2. 语义区分注释显式化：`rejected`=人工判定不可用（终态），`deprecated`=过时被替代——两者都不物理删除，零复用自然淘汰，量大再归档。
  3. 负样本反哺：`matchVerified` 前先查 `status='rejected'` 向量近邻，命中（距离 < 同一阈值）→ 直接跳过复用走完整重规划（对语义过近的 rejected 规避）。
- **已确认无需动**：完整规划 few-shot 只取 verified（`findTop3ByStatusOrderByLastUsedAtDesc`），rejected 不会混入。
- **验收**：拒绝一个 draft → plan 状态 rejected、待审核清单消失；用相同意图再问 → 不命中复用、走完整规划。

---

## P3 领域事件化消除轮询（中，预计 2 天）

- **落点**：
  - contract：`stock-calculator-contract/.../MqKey.java` L52-61（加 `EVENT_ANNOUNCEMENT_DONE_PREFIX = "event.announcement.done."` 等）；
  - main：`ClsArticleService.java` L76-80（财联社日报入库完成，已有 ArticleSavedEvent 进程内事件可挂）、`AnnouncementResultService.ingestDone` L92-128（订阅公告 DONE）——事务提交后发布 MQ 领域事件；
  - orchestration：新 `DomainEventFaninListener`（仿 TaskResultEventListener L28-119，**一个 fan-in 队列**绑 `event.*`，不按用户建队列）+ `Executor.executeMqWait` L195-206（节点 schema 扩展）。
- **做法**：
  1. 新 exchange（建议 `stockcalc.events` topic，与 `stockcalc.tasks` 任务指令交换机分离，语义不混淆）；
  2. mq_wait 节点 DAG 写法升级：`{event:"announcement.done", filter:{stock_id:"600519"}, timeout_seconds:86400}`——唤醒匹配从「trace_id 单实例回调」升级为「事件类型 + filter 匹配挂起实例 params」；
  3. Fan-in listener 收到事件 → 查 waiting 实例 → 匹配 event 类型 + filter 字段与 params 子集相等 → 唤醒对应 mq_wait 节点；无匹配实例则丢弃（事件不持久化）；
  4. 兼容性：**现有 TaskResultEventListener 按 trace_id 唤醒路径保留不动**（task.completed.* 语义继续有效），新 listener 只管领域事件；定时触发类场景仍走 notify reminder 事件体系。
- **终态**：编排器除 `MqWaitTimeoutScanner`（超时兜底，保留）外零轮询。
- **验收**：DAG 写 mq_wait 等公告 → 手工触发公告入库 → 实例被事件唤醒续跑；无关 stockId 事件不误唤醒。

---

## P4 Planner 能力锚定与前置澄清三件套（中，预计 2 天）

### ① 意图模板占位符抽象
- **落点**：`Planner.normalizeIntent` L74-90（prompt 加 `intent_template` 输出：数字/实体→`{slot_name}` 占位）；`PlanEntity`（加 `intent_template` 列）+ `orchestration-schema.sql`；`matchVerified` 的向量锚从 intent_text 换为 **intent_template 的 embedding**。
- **迁移策略**：verified 池近空 → **直接清空存量 plan 的 embedding 重算**（零迁移成本，窗口就是现在；此决策依赖探查事实 #6）。
- **修复目标**：「茅台100年 vs 宁德5年」参数污染——模板锚命中后由 fillParams 填具体值。
- **与 P1-1 合并点**：normalizeIntent 是同一次 LLM 调用，intent_template + slots 一次 prompt 改造同时产出，**建议 P4① 提前与 P1-1 合并执行**。

### ② 能力锚定改写
- **落点**：`Planner` 规划 prompt（注入 `tool_registry` 的 plannable 工具清单——`ToolRegistry` 现成数据源）；规划输出增加 `feasible(yes/partial/no) + rewritten_intent + gap`。
- **语义**：no → 显式报「能力受限」错误码返回用户（不落 plan）；partial → 降级说明并请求确认（可挂 HITL 或直接提示）。

### ③ 前置能力清单 + list_capabilities
- **落点**：mcp/copilot 侧新增 `list_capabilities` 查询工具；数据源 = verified plan 池按 `intent_domains` 聚合（**不预存、不穷举组合**，池即能力卡片事实源）；任务完成后按 domain 邻域实时生成推荐。
- **依赖**：①的 intent_template 是能力卡片的数据结构前提（模板即卡片描述），故 ③ 在 ① 后。

---

## P5 Executor 并行执行调度（大，独立立项，预计 3~4 天）

- **落点**：`Executor.java` L92-123（主循环重写）、L269-308（foreach）。
- **拆两个子步**：

### P5-1 depends_on 拓扑 + 并行调度
1. 解析节点 `depends_on`（Planner L170 已要求 LLM 输出，产出端就绪）→ Kahn 拓扑分层；
2. 就绪集合（全部上游 done）内的节点**并行执行**（Java 21 虚拟线程，executor 内部线程池，任务级 advisory lock 不受影响——它锁的是同 plan 的**任务实例**串行，不是节点级）；
3. 汇聚语义：多上游全部 done 才触发下游；任一上游 failed → 下游 skip + 实例 failed（沿用现 fail() 路径 L325-330）；
4. 断点续跑兼容：`restoreNodeOutputs` 已按 node key 注入跳过，并行化后保持逐节点 key 粒度不变。

### P5-2 foreach 完整语义（§八 L186）
1. 子项并行展开（复用 P5-1 的调度设施）；
2. `on_item_failure`: continue / abort 双语义；
3. `min_success_ratio`（如 0.8）：收束时按成功占比判节点成败；
4. 子项级重放：重入时跳过已成功子项（`<node_id>:<index>` key 已落 node_states，L301-302，粒度现成）只重跑 failed 子项。
- **验收**：现网 2① 异动归因 DAG（多股并行归因 → 汇总）跑通；构造部分失败场景验证 continue + ratio 收束 + 重放只补失败子项。

---

## 二、里程碑与依赖图

```
P1-1 slots ──┬──（建议与 P4① 合并：同一 prompt 改造）
P1-2 use_count ── P2（同挂点）
P1-3 match_log ── 独立
P1-4 draft 去重 ── 独立
P1-5 冒烟闭环 ── 复用 P1-2 的终态回调位置
P2 ── 独立（依赖 P1-2 完成后做更顺）
P3 ── 完全独立，可随时并行
P4① ── 依赖决策：embedding 锚切换 + 存量重算
P4②③ ── 依赖 P4①
P5 ── 最后；2① 异动归因 DAG 前置
```

## 三、验证口径

- 模块测试：`mvn -pl stock-calculator-orchestration test`（含 `OrchestrationSmokeGateE2ETest` 五场景冒烟门禁——P5 改主循环后必跑）；
- 全量：`mvn test`（contract 改 MqKey 后需全模块编译验证）；
- P1-3/P1-4 涉及 schema 变更：确认 `orchestration-schema.sql` 幂等执行（启动自跑）在既有库上补表/补列不报错。

## 四、待拍板事项（开工前确认）

1. **P1-5 冒烟标识机制**：plan_dag 标记 vs task params `purpose:"smoke"`；dry_run 机制是否已实现（探查未见实现体，若无需先明确「真实小参数+低风险白名单」降级方案）。
2. **P3 filter 匹配语义**：filter 与 params 相等匹配（精确）够不够？是否需要支持 `$.params.xxx` 引用表达式——建议 v1 精确相等，表达式随需再加。
3. **P4① 存量 embedding 重算**：需用户确认「清空 verified 池 embedding 重算」可接受（当前池近空，损失≈0，但请确认没有已人工上架的宝贵 plan）。
4. **P1-3 match_log 保留策略**：只追加不清理先跑着，量大（如 >10w 行）再加分区/归档。
