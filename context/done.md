---
memo: done
format: v2
---

# 完成列表
- [2026-09-16] toolbox check 门禁 secret 形状扫描落地：7 类正则逐行扫描、命中拒收不搬家、collect/check 双接线、自检金丝雀好坏样本、SPEC v1.2 宪法条款 (域: misc)
- [2026-09-18] (功能) task-unify 第一步：CALENDAR 概念消化——CalendarTaskClaimScheduler 独立组件 + PullLoopWatchdogTask 瘦身只留 LOOP 半区，main 384 测试全绿，用户已验收 (域: task-unify)
- [2026-09-18] (功能) task-unify 第二步：合表+executor 策略化——app_task_config 6 行并入 pull_task_config CALENDAR（ttl_ms=0）、CalendarTaskClaimScheduler 统一认领（注册表命中进程内/未命中 MQ）、schema 幂等迁移段+DROP、main 384 全绿 (域: task-unify)
- [2026-09-18] (文档) pull-loop-unification.md §8/§9 加存档注 + 新增 §10、module-split-test-plan/announcement-rag/news-search implementation 联动更新，lint 通过（memory-summary.md 缺口为存量债）(域: task-unify)
- [2026-09-18] (风险) 第二步单步面宽风险解除：测试对账 384=384−7−8+15 全绿收口；存量库 DROP 迁移属破坏性动作留人工执行 (域: task-unify)
- [2026-09-22] tool_registry 补 execution_mode 能力标签（sync/async_long），DDL + ToolDescriptor + 自注册同步 (域: orchestration)
- [2026-09-22] dispatch 网关工具落地（步5）：sync 直调 ToolInvoker / async_long 转 create_task / 低置信返回候选澄清，含 DispatchRouterTest (域: orchestration)
- [2026-09-22] copilot 只挂 :18083 dispatch 单工具：main application.yml 移除 :18081/:18082 直连池，统一 orchestration-dispatch 连接 (域: orchestration)
- [2026-09-22] 数字人 MCP 调用链路定案落地：前端不直连 MCP，后端经编排器 :18083 dispatch 路由调 kb 工具 (域: mcp-blogger-kb)
- [2026-09-22] 编排器鉴权通行证模式落地：PassportFilter 静态 Bearer token 校验（空配置放行） (域: mcp-blogger-kb)
- [2026-09-22] 步6-0 TraceId 全链路透传基建：main 生成 W3C traceparent 经 MCP 请求头下传（McpTraceHeaderConfig），编排器 PassportFilter 捕获 + TraceIdHolder 透传优先，$ctx env.trace_id 单测验证 (域: orchestration)
- [2026-09-22] 步6-1 通道映射块：user_async_task_log 审计表 DDL+Entity+Repository，AsyncTaskChannelService 生成 correlationId 兼作 orchestration traceId 双写映射，pg_advisory_xact_lock 同 plan 串行 (域: orchestration)
- [2026-09-22] 步6-2 MQ 节点：TaskMessageSender（TASKS 交换机，messageId=traceId 重放幂等，payload 仅 correlationId 脱敏）+ Executor mq_send/mq_wait 节点（waiting+wait_deadline 挂起、MqWaitSuspendedException 断点续跑）+ TaskResultEventListener 唤醒 + MqWaitTimeoutScanner 超时扫描，contract 增 task.completed./failed. 事件 key，单测全绿 (域: orchestration)
- [2026-09-22] 步6-3a 真异步执行（orchestration 侧）：dispatch/create_task 存实例即发 task.orchestration.run 启动请求、即刻返回 RUNNING 契约；TaskRunnerListener 消费侧调 Executor.run（幂等拒重放）+ 终态回发 task.completed./failed.orchestration；编译与既有单测全绿 (域: orchestration)
- [2026-09-22] 步6-3b SSE 状态机（main 侧）：AsyncTaskResultConsumer 消费 async.task.result.q（x-expires 24h，绑定 task.completed.*/failed.*，@Lazy(false) 防静默失效）→ 查 user_async_task_log 还原 user → finalizeTask CAS 终态回写 → SseEmitter 注册表推 task_result 事件并收口；订阅端点 GET /api/copilot/async-tasks/{correlationId}/events；编译与单测全绿 (域: orchestration)
- [2026-09-22] 步6-4a 用户维度限流卡 main 侧：AsyncTaskRateLimiter 双闸门（并发查 RUNNING 计数 + 频控查时间窗创建数，数据源=user_async_task_log）挂 createAsyncTask 入口前置，阈值配置化 copilot.async-task.rate-limit（默认 2 并发/60s 窗 60 次），超限 BusinessException 429；编译与单测全绿 (域: orchestration)
- [2026-09-22] 步6-4b 匿名通道 GC 双保险：x-expires 24h 三队列核对落盘（启动/唤醒/终态，durable 无 auto-delete）+ AsyncTaskLogGcScanner @Scheduled 5min 扫超期 RUNNING 置 TIMEOUT（阈值=频控窗 20 倍，释放限流并发额度）；编译通过 (域: orchestration)
- [2026-09-22] 步6 冒烟 Gate（硬性门禁）双半区 E2E 全绿：orchestration OrchestrationSmokeGateE2ETest 五场景（挂起/唤醒续跑/回发路由/超时收口/失败）+ main AsyncTaskResultSseE2ETest（SSE task_result 推流 + 审计 RUNNING→DONE/FAILED CAS 回写）；顺修 orchestration 启动阻断——pom 补 spring-boot-starter-webmvc/restclient（Boot 4 拆模块 RestClient.Builder 缺失） (域: orchestration)
- [2026-09-22] 步6 冒烟 Gate 五场景 E2E 全绿（SMOKE_E2E 门控，真实 PG/LavinMQ）：①mq_wait 挂起 waiting+deadline ②终态事件唤醒断点续跑至 done ③task.completed 路由可达 ④waitDeadline 扫描置 timeout ⑤节点抛错置 failed；Dummy=sleep 节点（120s 上限防滥用）；拦下两处真 bug：PlanEntity/TaskInstanceEntity 五个 JsonNode 字段缺 @JdbcTypeCode(SqlTypes.JSON)（上下文起不来）、MQ 发送 POJO 直发 JDK 序列化（改信封 JSON 字符串统一三端形态） (域: orchestration)
- [2026-09-22] 步7-1 HITL 审核：HitlReviewService（plan 详情=DAG+最近5次执行摘要、verify 仅 candidate 可上架/deprecate、approveTask/rejectTask 挂起回调）+ HitlReviewController（/api/orchestration/hitl/*，通行证守门）+ Executor 新增 hitl_wait 节点（复用 mq_wait 挂起/断点续跑/超时扫描机制，人工决策走 REST 回调不走 MQ）；编译与单测全绿 (域: orchestration)
- [2026-09-22] 步7-2 mq 可观测工具面：MqObservabilityTool 三工具（queue_stats 队列积压/消费者数、message_peek 死信抽样窥视不消费、message_trace 按 traceId 查实例与节点状态摘要）注册进 MCP 工具面经 dispatch sync 直达，不设 task 状态类工具防双状态源；编译与单测全绿 (域: orchestration)
- [2026-09-22] orchestration 单测收口：CtxEvaluatorTest（三命名空间寻址/output 哨兵/数组下标/越界拒绝/slim 三策略/foreach 展开 12 用例）+ ExecutorBranchTest（switch 命中/default/双缺拒绝 + foreach $item 替换/空数组/未注册 6 用例）+ PlannerFillParamsTest（__missing/required 缺失/全齐/LLM 异常 4 用例）；全量回归 EXIT=0 (域: orchestration)
- [2026-09-22] mcp-blogger-kb system prompt 拼装口径落地：PersonaPromptInjectionService 经 dispatch 调 kb_persona 渲染语气规则段（金句≤5 学句式不引数据），AiChatOrchestrationService 记忆段后注入（AskRequest.blogger 非空触发，无卡/降级零感知）；usage.md 更新（dispatch 单连接现状/kb_persona 工具行/拼装样例段）；lint+编译全绿 (域: mcp-blogger-kb)
