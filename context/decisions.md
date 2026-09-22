---
dev-loop: decisions
format: v1
epic: global
total-merged: 0
last-merge: none
---

# 项目决策与洞察录

> 定位：方案权衡与 AI 洞察的历史档案（人类回顾用）。**非权威**——不进裁决链，与 memory.md 冲突时以 memory 为准；AI 只写不读（恢复流程不读），SSOT 修正不回改本文（历史不改写）。由 `/adr` 写入：按 epic 分节 + `## misc` 兜底，节内最新在上；条目四段——场景/痛点、可选方案（含否决理由）、最终决定、AI 洞察。

## orchestration

### 2026-09-22 统一入口走方案 3：dispatch 网关 + 快慢车分流，直落不过渡
- **场景/痛点**：copilot 直连 :18081/:18082 工具池 vs 全走编排器 create_task 双通道并存，LLM 运行时承担通道路由心智，单人维护两套调用逻辑易混乱。
- **可选方案**：①工具描述带边界约束让 LLM 选通道（否决：提示词负担+路由不稳）；②关键词拦截强制引导（否决：规则散落两处）；③统一入口+后端分流（选定）；过渡期双池并存（用户否决：一个人维护不了两套逻辑最终自己混乱）。
- **最终决定**：直落方案 3 不过渡。copilot 只挂 :18083 的 dispatch 单工具；网关按 tool_registry 的 execution_mode 标签（sync/async_long）+确定性规则分流——sync 直接代调 ToolInvoker 秒回，async_long 转 Planner/Executor；低置信返回候选清单澄清不硬路由。main 移除 :18081/:18082 直连池。访问延迟优化后置。已修订 agent-orchestration.md §四/§6.1/§九/§十/§十一，todos 步5 拆为三条（execution_mode 标签 / dispatch 网关 / copilot 收敛单连接）。
- **AI 洞察**：方案 3 没有消灭路由判断，只是把它从 LLM 工具选择移到网关分流器；工具描述纪律的消费者从 LLM 换成分流器，分流器是新增核心组件（确定性规则起步，勿上分类模型），误判样本应回流规则库并带单测。

## misc
