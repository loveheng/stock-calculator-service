---
dev-loop: memory | devlog | lessons | decisions
format: v1
epic: free-canvas
total-merged: 1
last-merge: 2026-09-25
---

# free-canvas · 自由画布后端对接（v3 代理模式）

## 事实源
- 契约唯一权威：docs/architecture/free-canvas.md（v3 2026-09-24 定稿，§3.5 已按下列 SSOT 修正改写）

## 落点定案（2026-09-24 探索收口）
- MCP 侧（stock-calculator-mcp）：工具注册 = `McpToolConfig.toolObjects` 显式列举追加 + `@Tool` 注解（样例 StockDailyTool），**非自动扫描，漏挂即 Tool not found**；读穿复用 `quote/QuoteSyncService.ensureBars`（全量 days*2+30 / 增量 last-10 重叠）+ `EastmoneyDailyClient(fqt=1)` + `QuoteDailyRepository`；quote_daily 唯一约束 `(stock_id,trade_date)`，adjust 硬编码 qfq。
- orchestration：新工具须在 `ToolRegistry.MCP_SEEDS` 加种子（endpoint `http://localhost:18081/sse`）；main 经 `:18083` dispatch 工具（intentText 含工具名 → SYNC_DIRECT 确定性路由）；超时梯队 ToolInvoker 8s / dispatch 10s / main 15s。
- main：broker/ 平级域（controller/service/util/dto），`WebConfig` 加 `/api/broker/**`；限流仿 SearchRateLimiter（按端点拆桶 + retryAfterSeconds，禁 import copilot/search 内部）；信封/异常走 `common.ApiResponse` + `GlobalExceptionHandler`（429 自动带 data.retryAfterSeconds）；鉴权 `@RequestAttribute("authUserId")`；SSE 样例 = CopilotController 双映射 + `AiChatOrchestrationService.askStream`；fullCode 归一化新写 `FullCodeNormalizer`（无格式符→补 sh.600000 类全码），字典校验用 `crawler/StockDirectoryApi`（其注释称"覆盖不全仅兜底"，与契约 400 强校验存在张力，按契约执行并留风险）。

## 一期实现现状（M1~M4 全部代码完成）
- M1 数据主通道：mcp `fetch_kline`（qfq 读穿入库 / raw 纯读穿不入库；出口频控 `RateLimitedQuoteClient` 令牌桶 5QPS burst20；重叠段 Epsilon 漂移阈值比对→不一致自动 resync 重灌；连续性缺口日志）+ orchestration 种子；main `/api/broker/klines` 读穿代理 + `/api/broker/indicators` 能力端点 + `BrokerRateLimiter` + 5s 请求合并窗口。
- M2 指标计算：mcp `IndicatorSeriesService`（ta4j 逐根序列，macd/kdj/boll；暖机期以 null 占位 33/10/20；不足 minBars 整段返回 null）+ `ComputeIndicatorsTool`（纯计算无外部 IO）；main `POST /api/broker/indicators/compute`（256KB 硬上限→413、compute 桶 30/s、切片 ≤120 且升序校验、白名单外 400）；kdj minBars 对齐 10。
- M3 画布对话：copilot 基包门面 `CopilotAskApi`（中性签名 askStream/askBlocking，Modulith 合法出口）；main `POST /api/broker/ask` 双映射（SSE 流式 + JSON 阻塞回落），ask 桶 2/5s + 256KB + cid 幂等透传 + fullCode 字典校验；fullCode/canvasContext/klines 摘要挂 contextSummary（只进 Prompt 不落库）。
- M4 画布监控（B·调度路径）：main broker 域 `broker_monitor_task` 表（postgres/schema.sql）+ `job.broker.monitor.check` 播种行（每分钟）；`MonitorService`（并发 ≤5 DB 计数 + 幂等 start + 归属校验 stop）+ `MonitorCheckTask`（per-task 60s 节流 + 30min 告警冷却 + PRICE_BELOW 判定 + `BrokerAlertPublisher` 直投 notify.push 复用 Web Push 全链）+ `/api/broker/monitor/{start,stop}`。
- 验证水位：编译通过，单测 main 436 / mcp 61 全绿（含 Modulith 校验）；mcp 新增指标三项单测（序列对齐/暖机/minBars 边界）。

## 关键决策（SSOT）
- adjustType=raw 不落库：quote_daily 唯一约束只容单一复权基准，raw 走 `fetchRawWindow` 纯读穿，防两基准硬拼；coverage 字段如实标注。
- klines 只摘近 10 根进提示词：120 根全量塞 prompt 会爆炸；契约 §3.2 本就声明 agent 需历史走路径 B 读自家库。
- §3.5 监控架构：旧结论（orchestration `async_long` → Executor DAG 循环 `mq_wait` tick）已废弃；新结论 = B·调度路径（main 定时调度 + dispatch fetch_kline + notify.push 直投）。理由：Executor 无循环语义、无确定性建任务入口、无 stop 机制，改造代价高；监控状态属用户业务数据非行情数据，硬约束 #8（K 线域零状态）不破。

## 暂缓项
- §3.3 news、§3.7 annotations（契约已预留）；§8.2 连接池 per-connection 超时拆分（W1 压测随 M2/M3 一并看）。
- §5.2-1 agent 提示词配合项：系统提示词加「输出 execute_local_calc 动作无需等待结果」，属 prompt 模版内容（代码外落地），随 M4 后一并评审。

## E2E 实测结论（2026-09-25 本地容器全链）
- 起服口径：中间件容器（postgres/redis/lavinmq）常驻；`toolbox run jm -- mcp --daemon` → `orchestration` → `main`（前台 exec 会被会话回收，必须 --daemon；顺序错会因 MCP client 20s 握手超时起不来）。
- 实测通过项：401 无 token 拦截；`GET /indicators`（version=1 三项）；`GET /klines`（qfq / raw / 2015 早期区间均 200，1.5s 级）；`POST /indicators/compute`（40 根→macd 前 33 根 null 暖机、0.7s）；`POST /ask`（JSON 阻塞 11.3s、SSE delta 流首包 ~1s）；`POST /monitor/start`（幂等复用 id=1，落库 RUNNING）+ 每分钟调度判定 + PRICE_BELOW 命中 notify.push 直投 + `/monitor/stop`（STOPPED + 归属校验）；ask 桶并发 4 次 → 2 通过 2 个 429（retryAfterSeconds=1）。
- 实测暴露并已修：compute/ask/monitor 字典校验沿用 `existsByCode`（裸 6 位码 `existsById` 永远落空）→ 对合法 fullCode 全量 400「未收录」；统一改 `existsBySixDigit` 并补回归单测。契约 §三 已补记该口径与成因。
- 状态码口径复核：契约 §三统一约定即「HTTP 200 + 信封 code」，实现一致（仅 401 为例外真实状态码），无需改动。

## 风险记录
- ~~StockDirectoryApi 字典覆盖不全 vs 契约 400 强校验~~（2026-09-25 收口）：四端点统一 `existsBySixDigit` 6 位尾匹配后 sh600000/sh600519 实测放行；字典本身仍称"覆盖不全"，若个别代码误杀再评估形状白名单兜底。
- ToolInvoker 8s 上限 vs 全量拉取：klines 实测 1.5s 级（库命中/单窗口读穿），未触预算；超预算再升级为 §8.2 per-connection 超时议题。
- ask 时延：JSON 阻塞实测 11.3s，逼近 main 15s 硬超时；并发或弱网下有超时风险——§8.2 压测（W1）时重点看 ask P99，必要时前端优先走 SSE 变体。

## 断点
- [断点] 下一步：前端联调——coverage 空洞降级核对 + §5.2-1 agent 提示词配合项落地（服务端四端点已 E2E 通过，可作为联调基线）
