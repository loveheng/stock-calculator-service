---
memo: todos
format: v2
---

# 待办列表

## orchestration
- [ ] [2026-09-22] (功能) mq_send/mq_wait 节点落地：mq_send 按 contract 契约构造 payload 下发 task.* 消息，mq_wait 挂起实例由 MQ 事件唤醒（timeout + on_timeout 必填已拒载防御），含重放幂等键防重复触发 (src: ai)
- [ ] [2026-09-22] (功能) 异步执行 + 任务完成事件：长时任务脱离 create_task 同步阻塞，节点流转事件发 MQ 给 copilot 转 SSE 中间态，完成事件推送结果 (src: ai)
- [ ] [2026-09-22] (功能) HITL 审核 API（步6）：plan 详情（DAG+冒烟真实输入输出/耗时）+ 确认上架 verified 接口，draft→candidate 自动冒烟衔接 (src: ai)
- [ ] [2026-09-22] (功能) tool_registry 补 execution_mode 能力标签（sync/async_long），DDL + ToolDescriptor + 自注册同步（方案3分流地基） (src: ai)
- [ ] [2026-09-22] (功能) dispatch 网关工具落地（步5，方案3直落不过渡）：统一入口，sync 按标签直接代调 ToolInvoker 秒回，async_long 转 create_task；低置信返回候选清单澄清不硬路由；分流规则单测 (src: ai)
- [ ] [2026-09-22] (功能) copilot 只挂 :18083 dispatch 单工具（步5收尾）：main application.yml 移除 :18081/:18082 直连池、保留会话身份注入口径，端到端跑通首条链路（跨模块改动） (src: ai)
- [ ] [2026-09-22] (功能) mcp 模块 mq/ 子包工具面（步7）：message_trace/queue_stats/message_peek 等可观测工具，不设 task 状态类工具防双状态源 (src: ai)
- [ ] [2026-09-22] (测试) orchestration E2E 端到端：四服务（main/mcp/orchestration+PG/Rabbit 容器）跑通「公告订阅+形态」首条链路，验证 mq_wait 长时挂起与复用缓存全链路 (src: ai)
- [ ] [2026-09-22] (测试) orchestration 单测补齐：$ctx 求值器/Executor switch-foreach/Planner 填槽校验纯逻辑类用例 (src: ai)
- [ ] [2026-09-22] (风险) orchestration 多实例并发未处理：Executor 同步执行无分布式锁，同 plan 并发实例可能重复调外部接口；MQ 通道（user.{userId}.{taskType} 队列管理 §6.4）未落地 (src: ai)
- [ ] [2026-09-22] (文档) agent-orchestration.md 联动修订：status 仍 draft，§十落地顺序标进度（步0-5已完成），§六数据模型与实际 DDL 核对 (src: ai)

## mcp-blogger-kb
- [ ] [2026-09-22] (功能) 数字人客户端 MCP 接入与调通：连 :18081/sse，消费 kb_search/kb_persona；先确认客户端仓库路径与技术栈再拆解 (src: ai)
- [ ] [2026-09-22] (功能) system prompt 拼装口径落地：事实引书（kb_search 经典书）+ 观点标博主（kb_search 出处）+ 语气按 persona 卡（kb_persona 进 system prompt） (src: ai)
- [ ] [2026-09-22] (文档) docs/mcp/api.md 与 design.md §九 补 kb_persona 工具契约（M2 收尾遗留的文档联动，待用户确认后执行） (src: ai)
- [ ] [2026-09-22] (文档) docs/mcp/usage.md 接入指引更新：MCP 端点现状（/sse）、kb_persona 用法、system prompt 拼装样例 (src: ai)
- [ ] [2026-09-22] (风险) 多博主人格合成（每博主一卡 + 离线 LLM 融合跑一次）v1 未做，属同机制延伸玩法，v2 再做 (src: ai)

## misc
- [ ] [2026-09-16] (功能) toolbox 僵尸淘汰消费端：list 基于 usage-ledger 吐 ⚠ 退役建议（连续失败 ≥3 或长期零调用）——触发条件：池内工具 >10 (src: 用户)
- [ ] [2026-09-16] (功能) toolbox run <name> 规范手动调用入口，补齐 manual 直连调用的遥测盲区——触发条件：出现需统计手动用量的场景 (src: 用户)
- [ ] [2026-09-16] (功能) toolbox 管道组合器：等真实串联场景出现 ≥2 次再做；前置——spec 先区分「结论输出」与「数据载荷」两种 --json 语义 (src: 用户)
- [ ] [2026-09-16] (功能) 本地凭证隔离区：等第一个真实凭证需求（DB/私有仓库/三方 API）再做；口径——优先 OS keychain（secret-tool），严禁自造加密 (src: 用户)
- [ ] [2026-09-16] (风险) agent-toolbox 不做事件总线/fswatch 守护进程/webhook 入站监听——与「无常驻进程、无 AI 也可人工操作」哲学冲突，后续重复提案直接引用本条否决 (src: ai)
