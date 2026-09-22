---
dev-loop: devlog
format: v1
epic: mcp-blogger-kb
total-merged: 0
last-merge: none
---

# mcp-blogger-kb 开发过程日志

（散修/小改动按行追加；≥5 条自动归并进 memory.md）

## 追加区
- [2026-09-20] [变更]: M1-text 落地——kb_source 注册表（/admin/source 注册即灌入/移除停更保数据/清单）+ KbWeiboBackupParser（微博备份一条=一个观点单元，纯媒体条目与标记行剔除，published_at 提取）+ KbIngestService.ingestSource（博主伪书 category=blogger + source_id 关联，与灌书共用批量向量化 embedAndWrite，幂等=按书 truncate 重载）+ kb_search 过滤 removed 源；kb_book/kb_chunk 补列 source_id/published_at（ALTER IF NOT EXISTS）
- [2026-09-20] [变更]: E2E 实证——杀旧实例释放 18081 后新构建启动 validate 通过；注册「麻辣新鲜」微博备份 59 条→55 块全带 published_at（09-06→09-20）；DELETE 后 55 块保留（停更保数据）、重注册重灌 55/55；MCP 三步协议真调 kb_search「黄金的避险功能」命中「麻辣新鲜/微博 2026-09-06 06:45」出处与书籍段落同榜；单测 33/33
- [2026-09-20] [变更]: M1b RSS 轮询落地——KbRssFeedParser（JDK DOM 零依赖，RSS2.0/Atom 通吃，标题型 feed 的 description 复读标题去重，块=标题+链接；RFC-822/ISO 双时间容错折算系统时区；原拟 Rome 按零新依赖+标准实现优先改定）+ KbRssClient + KbRssPoller（kb.rss.* 门控默认 6h 逐源 fail-open）+ POST /admin/source/{name}/refresh 手动刷新；ingestSourceRss 按 content_hash 增量只补新（chunk_index 续排，chapter_path=条目标题首行）。E2E：注册「政策法规」（gov.cn 标题型 feed）首拉 11/11、二刷 0 新 11 跳、11 块带 +08 折算 published_at、MCP kb_search 真调命中带链接出处；单测 38/38
- [2026-09-21] [变更]: M2 persona 提炼管线落地——KbLlmClient（OpenAI 兼容原生 REST，复用 DEEPSEEK_* 三键）+ KbPersonaService（全量 chunk→风格层卡+10-20 原文金句→persona 伪书覆盖写，kb_book 补 persona_model/persona_generated_at 留档）+ kb_persona MCP 工具 + POST /admin/source/{name}/persona；kb_search 关键词路与 kb_book_list 显式排除 persona（风格不进检索层）；单测 47/47（新增 9）；E2E 因需重启服务待用户确认后复跑
- [2026-09-21] [变更]: KbLlmClient 三轮实测修复——HTTP/1.1 显式工厂（JDK HttpClient HTTP/2 对 SiliconFlow 抛 Request cancelled）+ byte[] 收包 UTF-8 解码（octet-stream 无 charset 拒转 String）+ max_tokens 4096（默认值小会截断 JSON）+ 读超时 600s（该模型长生成约 5 分钟）
- [2026-09-21] [变更]: M2 E2E 收口——用户换模型 step-3.7-flash 后单调用真跑成功（卡+18 金句，persona_model/persona_generated_at 留档，金句长度合规）；修复 McpToolConfig 漏挂 KbPersonaTool；MCP 真调 tools/list（7 工具）/kb_persona/kb_book_list/kb_search 全通过，persona 不进检索层与书目清单实证；中间过程验证了 8192 max_tokens + finish_reason=length 检测 + 解析失败诊断日志有效
- [2026-09-21] [SSOT 修正]: persona 提炼调用形态从「两段拆分调用」改回「单次调用」——用户换用 step-3.7-flash 后输出纪律可靠，拆分方案已还原（保留 8192 max_tokens、finish_reason 检测、代码侧钳制与解析失败诊断日志）
- [2026-09-21] [变更]: orchestration 模块步0-步5 首轮落地（docs/architecture/agent-orchestration.md）——新 Maven 模块 stock-calculator-orchestration（:18083，父 POM 挂载，spring-ai mcp server+client/amqp/contract/JPA，库连 stock_mcp）；orchestration-schema.sql 三表幂等 DDL（tool_registry/plan/task_instance + vector 扩展 + plan HNSW cosine 索引 + trace_id 唯一约束）；三 Entity（JSONB @JdbcTypeCode，embedding vector 列不映射走原生 SQL CAST——KbChunkRepository 同款 42P18 纪律）+ 三 Repository（plan 含 Filtered Vector Search：verified+needs_review=FALSE+intent_domains && 前置过滤）；统一工具面 ToolDescriptor/ToolRegistry（7 个 mcp 工具启动自注册 + 内存缓存 + schemaMatches 漂移比对）/ToolInvoker（mcp 走 starter 自动装配 McpSyncClient callTool，rest 走 RestClient 调 main :18080，X-Trace-Id 头贯穿）；Planner（意图规范化→向量匹配→廉价 LLM yes/no 校验→参数填槽硬校验→完整规划落 draft，LLM/嵌入客户端照 KbLlmClient/KbEmbeddingClient 套路自带 provider，D6 代价记账）；Executor（确定性 DAG：rest/mcp/switch 枚举路由/foreach 展开（子项 key=<node_id>:<index>）+ $ctx 三命名空间求值（params/nodes.<id>.output/env）+ mq_wait 无 timeout 拒载 Zombie 防御 + output_policy 落库瘦身）；TaskTool（create_task/query_task MCP 工具面 + OrchestrationToolConfig 挂载）。./mvnw compile -pl stock-calculator-orchestration -am 通过（踩坑：Jackson 3 无 ObjectMapper.shared()，遍历用 propertyNames()/properties()；RestClient uri() 必须紧随 method()）。待办：mq_wait/mq_send 节点、异步执行+完成事件、HITL 审核 API（步6）、mcp mq/ 子包（步7）、E2E
