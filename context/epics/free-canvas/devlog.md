---
dev-loop: devlog
format: v1
epic: free-canvas
total-merged: 0
last-merge: none
---

# free-canvas devlog
- [2026-09-25] [变更]: E2E 实测修复——compute/ask/monitor 字典校验由 existsByCode（裸 6 位码 existsById 永远落空）统一改为 existsBySixDigit 尾匹配（此前三端点对合法 fullCode 全量误 400）；补 BrokerComputeServiceTest/MonitorServiceTest 回归锚点（含「命中通过」正向断言 + never existsByCode），main 单测 456 全绿
- [2026-09-25] [变更]: 本地容器全链 E2E 通过——mcp/orchestration/main 依序后台起（toolbox run jm -- <模块> --daemon）；klines(qfq/raw/早期区间) + indicators 能力端点 + compute(40 根 macd 暖机 33 null) + ask(JSON 11s / SSE delta 流) + monitor(start 幂等 id=1 + 每分钟调度判定 + PRICE_BELOW 命中 notify.push 直投 + stop 归属校验) + 401 拦截 + ask 桶 429 retryAfterSeconds=1 均符合契约；契约 §三 fullCode 条目补记校验口径与修订记录
- [2026-09-25] [验证]: 未执行: 历史变更本轮未重跑（当日变更自载验证：main 单测 456 全绿含回归锚点；本地容器全链 E2E klines/indicators/compute/ask/monitor/401/429 符合契约）
- [2026-09-26] [变更]: vision 域精简拆分——OCR 层（azure→ocrspace 责任链+MD5缓存，删 local-gemini 多模态）平移 mcp 模块 mcp.vision 包暴露 ocr 工具（McpToolConfig/ToolRegistry 种子注册）；main 经 dispatch 调用（BrokerDispatchClient 上移 common/McpDispatchClient，broker/vision 共用），/ocr-parse 收敛进 /process-image 同管道、端点全保留前端零改动；缺码补全 Smartbox 外呼换 stock:dict 字典镜像（DictStockCodeResolver）；超时梯队调档 orchestration 40s/main 45s；文档 ocr-llm.md v2.0 改写+README 索引同步
- [2026-09-26] [验证]: mcp 74 全绿（新增 OcrChainManagerTest 6 + AzureOcrServiceExtractContentTest 6）；main 449 全绿（删 3 失效测试，Facade 测试改 OcrViaMcpService 桩，补 McpDispatchClient import）；orchestration 60 全绿；docs-index-lint 仅存量遗留 2 缺条目（jpa-native-extraction/toolbox，非本轮产物）
- [2026-09-26] [变更]: /api/import/** 挂 AuthInterceptor 登录鉴权（WebConfig，与 /api/broker/** 同款；AuthInterceptor 放行 OPTIONS preflight 防跨域预检被 401）
- [2026-09-26] [验证]: main 单测 449 全绿（BUILD SUCCESS）
- [2026-09-26] [变更]: vision 域死代码清尾——删无消费者的 config/RestClientConfig（commonRestClient 唯一注入方 SmartBoxStockCodeResolver 已随拆分删除）；修正 3 处过时 javadoc（TradeDraftParser 指向 DictStockCodeResolver、LlmChainRouter 指向 mcp.vision.OcrChainManager、RestClientConfig 随删）
- [2026-09-26] [验证]: main 单测 449 全绿（BUILD SUCCESS）
- [2026-09-26] [变更]: vision 域注释痕迹清理——11 处 javadoc 去历史化（删「精简落定/平移/替代 Smartbox/决策 B12/拍板决策」等过程性表述，注释只描述现状语义），涉及 Facade/OcrViaMcpService/DictStockCodeResolver/ImportController/缓存两文件/dto/PromptFormatter/TradeDraftParser/VisionConfig
- [2026-09-26] [验证]: main 单测 449 全绿（BUILD SUCCESS）；grep 残留清零（仅存「旧缓存结构/旧契约」为现行功能语义）
- [2026-09-26] [变更]: vision 域结构重组收口——DictStockCodeResolver 提至 service/（删空 impl/ 目录）；删单实现接口 StockCodeResolver（并入具体类）；删 VisionConfig 空壳、VisionAiProperties 挪 vision 基包（删空 config/ 目录）。17→14 文件，零单文件目录、零单实现接口
- [2026-09-26] [验证]: main 单测 449 全绿（BUILD SUCCESS）；全仓 StockCodeResolver/vision.config 引用清零
