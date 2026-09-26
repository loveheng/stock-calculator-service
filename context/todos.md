---
memo: todos
format: v2
---

# 待办列表

## mcp-blogger-kb
- [ ] [2026-09-22] (风险) [long] 多博主人格合成（每博主一卡 + 离线 LLM 融合跑一次）v1 未做，属同机制延伸玩法，v2 再做 (src: ai)

## misc
- [ ] [2026-09-26] (功能) 聊天 agent 自主登记/取消监控提醒（guide P2「帮我设个提醒」闭环）：前置需定 dispatch 侧用户身份传递设计——/api/broker/** 挂 AuthInterceptor 且 monitor 端点 userId 取自会话，ToolInvoker REST 分支无凭证头，裸登记 REST seed 必 401；候选方案：a) dispatch 请求透传调用方会话凭证 b) main 侧新增 action 型内部端点（服务间凭证）c) copilot actions 随 done 交前端代调 (src: ai)
- [ ] [2026-09-25] (风险) [long] CninfoClient 北交所 secCode 推导未实证（8/4 开头映射缺失），北交所公告可能映射错交易所——需补实证后扩展 (src: ai, uncertainty-scan)
- [ ] [2026-09-25] (风险) [long] ClsArticleRepository excludeIds 空参契约仅靠注释维持（NOT IN () 非法 SQL），无断言守护，新调用方易踩——建议下沉到方法内防护 (src: ai, uncertainty-scan)
- [ ] [2026-09-16] (功能) toolbox 僵尸淘汰消费端：list 基于 usage-ledger 吐 ⚠ 退役建议（连续失败 ≥3 或长期零调用）——触发条件：池内工具 >10 (src: 用户)
- [ ] [2026-09-16] (功能) toolbox run <name> 规范手动调用入口，补齐 manual 直连调用的遥测盲区——触发条件：出现需统计手动用量的场景 (src: 用户)
- [ ] [2026-09-16] (功能) toolbox 管道组合器：等真实串联场景出现 ≥2 次再做；前置——spec 先区分「结论输出」与「数据载荷」两种 --json 语义 (src: 用户)
- [ ] [2026-09-16] (功能) 本地凭证隔离区：等第一个真实凭证需求（DB/私有仓库/三方 API）再做；口径——优先 OS keychain（secret-tool），严禁自造加密 (src: 用户)
- [ ] [2026-09-16] (风险) [long] agent-toolbox 不做事件总线/fswatch 守护进程/webhook 入站监听——与「无常驻进程、无 AI 也可人工操作」哲学冲突，后续重复提案直接引用本条否决 (src: ai)
