# 智能图片分析：多渠道 OCR + 免费 LLM 全链路管道

> 版本：v1.0（2026-09-01）
> 定位：`/api/import` 下「图片 → OCR 提取文本 → 清洗组装 → LLM 处理 → 业务结果」全链路的实现文档，覆盖 OCR 多渠道责任链、LLM 多渠道责任链与门面编排三层。
> 配套代码：`stock-calculator-main` 模块 `com.zzh.stock_calculator.llm` / `com.zzh.stock_calculator.vision`
> 状态：已实现并通过单测与本地桩测试（共 38 用例）；端点示例为契约示例，未含真实外部 API 冒烟记录。

---

## 0. 关键决策记录

| # | 决策点 | 结论 |
|---|--------|------|
| P1 | LLM 包归属 | LLM 是通用能力（非 vision 专属），独立顶级领域包 `llm`；OCR 留 `vision`。跨域只允许引用对方**基包**类型（`LlmChainRouter` 置于 `llm` 基包即模块 API），ModulithVerifyTest 守护 |
| P2 | LLM 协议 | Gemini（`/v1beta/openai`）与 Groq（`/openai/v1`）均为 OpenAI 兼容协议，抽 `AbstractOpenAiCompatibleLlmService` 基类；手写 JDK 原生 `HttpClient`（Connect 5s / Read 20s），不用 Spring AI 多实例（免费层渠道的错误分类与超时需要完全可控） |
| P3 | 重试策略 | LLM 默认单渠道**不重试**（`llm.max-attempts: 1`）：429 属 RPM/TPM 窗口限流，短退避重试大概率仍失败且占窗口，重试预算花在「渠道切换」上；OCR 保留 2 次（429 多为瞬时/请求过快） |
| P4 | 兜底定位 | `FallbackLlmService` 为诚实哑响应（`[降级响应]` 前缀固定模板，不调任何模型）；链尾放无模型规则引擎会编造结果，比诚实降级更危险。`llm.fallback.enabled=false` 时全链失败抛 503 |
| P5 | 工厂模式 | 砍掉 `OcrServiceFactory` / `LlmServiceFactory`：Spring 注入 `List<T>` + `@Order` 已覆盖全部场景，工厂唯一增量是「按枚举指定单渠道」，无第二使用场景不做双层间接 |
| P6 | 清洗原则 | `PromptFormatter` 保守清洗（零宽字符、行尾空白、压缩连续空行），**不做**正则智能断句/合并行——对表格类 OCR 文本有破坏性（数字与列错位） |
| P7 | 缓存 | OCR 层 MD5→文本 Caffeine 缓存（命中省免费额度）；LLM 层结果缓存暂不实现（P2 待定，同图同任务重复请求会重复耗额度） |
| P8 | 交易解析不迁移 | `/ocr-parse` 继续走 Gemini 多模态直读（表格结构识别远优于「OCR 扁平文本→LLM 重建」），与 `/image-ai` 并存、定位不同 |
| P9 | 结果缓存与强制刷新 | `/process-image` 新增「图片哈希→交易草稿」结果缓存（`vision.ai.*`，Caffeine 30m/128）；`useCache=false` 淘汰缓存并以审查模式 Prompt 重新处理。OCR 文本缓存刻意独立保留——同图重识别零增益只耗免费额度，重新处理的杠杆是提示词增强；降级模板输出不解析不缓存（`LlmChainRouter.isDegradedResponse` 识别） |

---

## 1. 概述与能力边界

系统为「手机截图 → AI 文本处理」场景提供两层免费渠道池 + 自动降级：

- **OCR 层**（vision 域）：Azure AI Vision（月 5000 次免费）→ OCR.space（月 25000 次免费）→ 本地 Gemini 兜底，提取图片纯文本；
- **LLM 层**（llm 域）：Gemini → Groq（llama-3.3-70b）→ 哑响应兜底，对文本完成业务任务；
- **门面层**（vision 域）：编排两层责任链 + 保守文本清洗 + 空文本拦截 + 阶段耗时统计。

| 能力 | 定位 | 渠道 |
|---|---|---|
| `/api/import/ocr-parse` | 交易流水结构化解析（**既有，未改动**，多模态直读） | Gemini 多模态 |
| `/api/import/ocr-text` | 通用 OCR 纯文本提取（单层：只走 OCR 链） | azure → ocrspace → local-gemini |
| `/api/import/image-ai` | 智能图片分析（全管道：OCR → 清洗 → LLM） | 两层责任链叠加 |
| `/api/import/process-image` | 交易流水图片→AI 草稿（全管道 + 结果缓存/强制刷新） | 两层责任链叠加 + 结果缓存 |

**边界内**：竖屏截图（复用交易截图校验：10KB~20MB、高宽比 0.45~5、高 ≤5200px）、免费层额度优先、单渠道故障透明降级。
**边界外**：多模态图片直传 LLM（本管道刻意拆成 OCR+LLM 两段，规避多模态超时与高额开销）、云端配额监控告警。

---

## 2. 总体架构

```mermaid
flowchart TD
    U["POST /api/import/image-ai (file + task)"] --> PRE["图片校验与预处理"]
    PRE --> F["ImageTextProcessingFacade 门面编排"]
    F --> S1["1. OCR 责任链 azure → ocrspace → local-gemini"]
    S1 --> CACHE[("MD5 哈希缓存 Caffeine 30m/256")]
    S1 -->|全败| X1["BusinessException 503"]
    S1 --> S2["2. PromptFormatter 保守清洗"]
    S2 -->|清洗后为空| X2["BusinessException 422 空文本拦截"]
    S2 --> S3["3. LLM 责任链 gemini → groq → fallback 哑响应"]
    S3 -->|全败且 fallback 关闭| X3["BusinessException 503"]
    S3 --> R["ApiResponse 文本结果 + 阶段耗时日志"]
```

降级语义：任一渠道抛出渠道级异常（`OcrChannelException` / `LlmProviderException`）时，调度器记录 **Warning** 日志并**自动流转下一渠道**，对调用方完全透明；仅当全部渠道失败（且兜底关闭）才抛业务异常。

---

## 3. 包结构与组件职责

```
com.zzh.stock_calculator.llm                     # 顶级领域包（模块基包 = 对外 API 边界）
├── LlmChainRouter.java                          # 责任链调度器（基包，供跨域调用）
├── service/
│   ├── LlmService.java                          # 策略接口：chat(systemPrompt, userMessage)
│   ├── LlmProviderException.java                # 渠道级异常（retryable 标记）
│   └── impl/
│       ├── AbstractOpenAiCompatibleLlmService.java  # OpenAI 兼容基类
│       ├── GeminiLlmService.java                # @Order(1) 首选
│       ├── GroqLlamaService.java                # @Order(2) 备用
│       └── FallbackLlmService.java              # @Order(3) 兜底哑响应
└── config/
    ├── LlmProperties.java                       # llm.* 配置族
    └── LlmConfig.java                           # @EnableConfigurationProperties

com.zzh.stock_calculator.vision                  # 本轮新增文件
├── service/
│   ├── PromptFormatter.java                     # 保守清洗 + 通用/交易两族 Prompt 模板（含审查模式增强段）
│   ├── TradeDraftParser.java                    # 模型输出 -> List<TradeDraftItem>（围栏清理 + 脏行隔离，两管道共用）
│   ├── ImageTextProcessingFacade.java           # 门面：编排 + 耗时打点 + 异常边界 + 交易草稿结果缓存
│   └── OcrChainManager.java                     # OCR 责任链调度器（前轮已建，MD5 缓存在此）
├── service/impl/                                # AzureOcrService / OcrSpaceService / LocalFallbackOcrService（前轮已建）
├── controller/ImportController.java             # /ocr-parse · /ocr-text · /image-ai · /process-image
└── config/
    ├── OcrProperties.java                       # vision.ocr.* 配置族（前轮已建）
    ├── VisionAiProperties.java                  # vision.ai.* 交易草稿结果缓存配置
    └── VisionOcrConfig.java
```

---

## 4. 核心机制

### 4.1 渠道契约与异常语义

两个策略接口刻意保持同构（`OcrService` / `LlmService`），每个渠道实现都必须回答三个问题：

| 接口方法 | 语义 |
|---|---|
| `channelName()` / `providerName()` | 渠道名，用于日志与全链失败原因汇总 |
| `isAvailable()` | 自我健康检查：enabled 关闭、缺少 Key/baseUrl 时返回 false，调度器**直接跳过**该节点 |
| 业务方法 | 成功返回结果（**绝不返回 null**）；失败抛渠道级异常 |

**严格区分两类失败**（异常处理严谨性的核心）：

| 结果类型 | 表现 | 调度器行为 |
|---|---|---|
| 业务空结果（图片无文字 / 模型输出为空） | 返回 `""` | 视为成功，立即结束链路（OCR 层并写缓存） |
| 网络/限流/服务端异常（429、5xx、超时、鉴权失败、响应畸形） | 抛 `OcrChannelException` / `LlmProviderException` | 记 Warning → （可重试则重试）→ 流转下一渠道 |

渠道级异常带 `retryable` 标记：`true`=瞬时故障（429/5xx/超时），可按配置重试；`false`=确定性失败（401/403），重试无意义直接换渠道。渠道异常不冒泡到 Controller——全链失败时由调度器统一抛 `BusinessException(503)` 并汇总各渠道原因。

**Azure 响应结构兼容**（2026-09-01 生产实测教训）：`AzureOcrService.extractContent` 依次尝试 ①`readResult.content`（imageanalysis 4.x）②`analyzeResult.readResult.content` ③`analyzeResult.readResults[*].content`（v3.2）④`readResult.blocks[*].lines[*].text` 逐行兜底——实测该资源响应的 `readResult` 只有 `blocks`（含 words/boundingPolygon）**没有 `content` 字段**，缺第④条时会把「有字图」静默解析成 `""`，被门面误判为空文本 422。全部路径未命中且响应含 `readResult`/`analyzeResult` 时打 WARN 暴露顶层结构（真·空图返回的是 `content=""` 字段存在，不会误触发告警）。

### 4.2 责任链调度与重试策略（OCR vs LLM 差异）

两个调度器（`OcrChainManager` / `LlmChainRouter`）逻辑同构：按 `@Order` 顺序遍历渠道 → 健康检查跳过不可用 → 成功即返 → 失败 warn+流转 → 全败抛 503。但**重试策略刻意不同**：

| 维度 | OcrChainManager | LlmChainRouter |
|---|---|---|
| 单渠道尝试次数 `max-attempts` | 2 | **1** |
| 依据 | OCR 429 多为瞬时/请求过快，短重试有效 | LLM 429 属 RPM/TPM **窗口限流**，短退避重试大概率仍失败且占窗口 |
| 结果缓存 | 有（MD5→文本，30m/256） | 暂无（P2 预留） |
| 渠道异常 | `OcrChannelException` | `LlmProviderException` |
| 全败 | 503，message 汇总原因 | 503，message 汇总原因 |

渠道优先级由实现类 `@Order(n)` 声明，Spring 注入 `List<T>` 时自动排序；两个调度器启动时均打印装配日志（渠道顺序可观测）。两个路由器**保持独立实现**（Rule of Three）：差异点实质（缓存、重试、异常类型），待出现第三个路由器再抽象。

### 4.3 OCR 图片哈希缓存

- 键：`MD5(图片字节)`（Spring `DigestUtils`）；值：识别文本（含空结果 `""`，空图同样省额度）；
- 实现：Caffeine `maximumSize=256, expireAfterWrite=30m`（`vision.ocr.cache-max-size` / `cache-ttl` 可调）；
- 命中：跳过全部渠道调用，日志 `OCR 文本缓存命中，跳过渠道调用`；
- 隔离：本地 Gemini 兜底走 `OcrExecutor` 的 `@Cacheable`（genericVisionCache），缓存键加 `txt:` 前缀，与交易解析（键=裸 MD5）互不污染。

### 4.4 OpenAI 兼容基类与错误分类

`AbstractOpenAiCompatibleLlmService`：Gemini 与 Groq 的请求/响应/错误结构完全同构（`POST {baseUrl}/chat/completions`，Bearer 鉴权，`temperature=0`，`stream=false`），子类只差三项配置（base-url / api-key / model）。关键实现约束：

- **HTTP**：客户端构建统一收敛在顶层 `util/HttpUtil.jdkRestClient(connect, read)`——JDK 原生 `HttpClient`（connectTimeout 在 builder）+ `JdkClientHttpRequestFactory`（readTimeout 在 factory），显式配置 Connect 5s / Read 20s，vision/llm 各渠道（`AzureOcrService` / `OcrSpaceService` / `AbstractOpenAiCompatibleLlmService`）共用；错误分类（429/5xx/401/403）属渠道业务语义，留在各渠道策略内实现；同步调用跑在虚拟线程上（项目已开 `spring.threads.virtual.enabled`），无平台线程阻塞问题；
- **JSON**：请求 `objectMapper.writeValueAsBytes` 手动序列化、响应手动解析，不依赖 RestClient 转换器自动探测；响应按 UTF-8 字节读取，规避中文乱码；
- **model 可配置**：Groq 免费层模型会轮换下线，`llm.groq.model` 必须保持配置化，勿硬编码承诺。

错误分类表（统一收敛为 `LlmProviderException`）：

| 响应/错误 | retryable | 调度行为 |
|---|---|---|
| HTTP 429（Retry-After 头捕获进异常消息） | true | 流转下一渠道 |
| HTTP 5xx | true | 流转下一渠道 |
| HTTP 401/403（Key 无效或过期） | false | 不重试，流转 |
| HTTP 200 + error 体（网关伪成功） | true | 流转 |
| 非 JSON 响应（如 HTML 网关页） | true | 流转 |
| 缺少 choices.message.content | true | 流转 |
| 连接/读取超时、其它网络 IO | true | 流转 |
| content 为空 | —（非异常） | 返回 `""`（业务空结果） |

### 4.5 PromptFormatter 保守清洗

清洗规则（刻意保守，防表格错位）：

| 规则 | 说明 |
|---|---|
| 不换行空格 `\u00A0` → 普通空格 | OCR 常见噪声 |
| 去零宽字符 `\u200B-\u200D` 与 BOM `\uFEFF` | 不可见污染 |
| 每行仅去行尾空白（`stripTrailing`） | 保留行首缩进，不改行内内容 |
| 连续空行压缩为单个空行 | 保留段落边界 |
| 整体 trim | 去首尾空白 |
| ~~智能断句/合并行~~ | **明确不做**：正则无法可靠处理表格文本 |

Prompt 组装：System 侧为通用约束（仅依据文本作答、严禁编造、容错 OCR 噪声、按指令格式输出）；User 侧为「任务指令 + 清洗后文本」模板（`【任务指令】/【待处理文本】` 两段式）。任务指令为空时门面使用默认指令「请整理并总结文本中的关键信息。」

### 4.6 门面编排与异常边界

`ImageTextProcessingFacade.processImageToAiResult(byte[] imageBytes, String taskInstruction)` 顺序编排三阶段并逐段打点，单条日志输出全链耗时：

```
图片→AI 全链路完成 (ocrCost=..ms, formatCost=..ms, llmCost=..ms, total=..ms, textLength=.., resultLength=..)
```

| 异常边界 | 触发条件 | 结果 |
|---|---|---|
| OCR 全链失败 | 所有启用渠道均失败 | `BusinessException(503, "所有 OCR 渠道均不可用：原因汇总")` |
| 空文本拦截 | OCR 成功但清洗后无有效内容 | `BusinessException(422, "图片中未识别到文字，已跳过 AI 处理")`——不消耗 LLM 额度 |
| LLM 全链失败 | fallback 关闭且其余渠道均失败 | `BusinessException(503, "所有 LLM 渠道均不可用：原因汇总")` |
| Prompt 为空 | systemPrompt/userMessage 空白 | `BusinessException(400)` |

### 4.7 交易草稿结果缓存与强制刷新（/process-image）

`ImageTextProcessingFacade.processImageToTradeDrafts(imageBytes, useCache)` 在通用管道之上增加**结果层缓存**：

| 缓存层 | Key -> Value | 归属 | 强制刷新时 |
|---|---|---|---|
| OCR 文本缓存 | MD5(图) -> 识别文本 | `OcrChainManager` 内置 | **保留命中**——同图重识别零增益只耗免费额度 |
| 交易草稿结果缓存 | MD5(图) -> List<TradeDraftItem> | 门面内置（`vision.ai.*`） | 淘汰后重算 |

useCache 语义：

- `useCache=true`（默认）：命中结果缓存直接返回（零 OCR/LLM 消耗）；未命中走 OCR → 清洗 → LLM → 解析 → 写缓存；
- `useCache=false`：淘汰结果缓存重新处理，且 System Prompt 追加**审查模式增强段**（逐字校对代码/数字、0/6/8 与 1/7 混淆、价格×数量交叉核对、宁少不编造）——这是 temperature=0 下驱动模型产出不同结果的唯一杠杆；空文本 422 拦截语义不变。

缓存写入时机：仅「成功解析」的结果入缓存（业务空结果 `[]` 同样缓存，与 OCR 链缓存 `""` 语义一致）；**降级模板输出**（经 `LlmChainRouter.isDegradedResponse` 识别，模板文本可配置故不硬编码前缀）与**解析失败**不缓存，避免污染。解析由 `TradeDraftParser` 承担（Markdown 围栏清理 + 二维数组映射 + 单行脏数据隔离跳过），与既有 `/ocr-parse` 的行映射语义一致。

---

## 5. API 端点

通用约定：HTTP 状态恒 200，业务结果经 `ApiResponse` 信封承载（`{code, message, data}`），前端按 `code` 分支。

### 5.1 POST /api/import/image-ai（全管道）

```sh
curl -X POST http://localhost:18080/api/import/image-ai \
  -F "file=@screenshot.png" \
  -F "task=提取文本中的股票交易记录并整理为表格"
```

| 参数 | 必填 | 说明 |
|---|---|---|
| file | 是 | multipart 文件，10KB~20MB，竖屏截图（高宽比 0.45~5，高 ≤5200px） |
| task | 否 | 业务任务指令，缺省「请整理并总结文本中的关键信息。」 |

成功：

```json
{ "code": 200, "message": "success", "data": "AI 处理后的文本结果" }
```

失败（信封 code 区分）：422 空文本拦截；503 OCR/LLM 全链失败（message 含各渠道原因）；400 参数/文件问题。

### 5.2 POST /api/import/ocr-text（仅 OCR 层）

```sh
curl -X POST http://localhost:18080/api/import/ocr-text -F "file=@screenshot.png" -F "language=chs"
```

返回 `data` 为 OCR 原文（含哈希缓存命中）；language 缺省取 `vision.ocr.language`（chs）。

### 5.3 POST /api/import/ocr-parse（既有，未改动）

交易流水结构化解析：Gemini 多模态直读出 `TradeDraftItem` 数组，**不走本管道**（决策 P8）。

### 5.4 POST /api/import/process-image（全管道 + 结果缓存）

```sh
curl -X POST http://localhost:18080/api/import/process-image \
  -F "file=@screenshot.png" \
  -F "useCache=false"
```

| 参数 | 必填 | 说明 |
|---|---|---|
| file | 是 | multipart 文件，校验规则同 5.1 |
| useCache | 否 | 默认 true；false 时淘汰该图片的结果缓存并以审查模式 Prompt 重新处理 |

成功：`data` 为 `List<TradeDraftItem>`（stockCode / stockName / direction / price / volume / tradeTime / status）；无有效流水时为 `[]`（同样缓存）。
失败（信封 code 区分）：422 空文本；503 OCR/LLM 全链失败或降级模板输出；500 模型输出非 JSON。

---

## 6. 配置参考

```yaml
vision:
  ocr:
    language: chs
    cache-max-size: 256
    cache-ttl: 30m
    max-attempts: 2
    retry-backoff: 300ms
    azure:
      enabled: ${AZURE_OCR_ENABLED:false}
      endpoint: ${AZURE_OCR_ENDPOINT:}
      api-key: ${AZURE_OCR_API_KEY:}
      api-version: "2023-10-01"
      connect-timeout: 5s
      read-timeout: 30s
      poll-interval: 500ms
      poll-max-times: 20
    ocrspace:
      enabled: true
      api-key: ${OCRSPACE_API_KEY:K82621831488957}
      url: https://api.ocr.space/parse/image
      engine: "2"
      connect-timeout: 5s
      read-timeout: 30s
  ai:
    # 交易草稿结果缓存（门面层）
    result-cache-max-size: 128
    result-cache-ttl: 30m

llm:
  max-attempts: 1
  retry-backoff: 300ms
  gemini:
    enabled: ${LLM_GEMINI_ENABLED:true}
    base-url: https://generativelanguage.googleapis.com/v1beta/openai
    api-key: ${GEMINI_API_KEY:}
    model: gemini-3.6-flash
    connect-timeout: 5s
    read-timeout: 20s
  groq:
    enabled: ${LLM_GROQ_ENABLED:true}
    base-url: https://api.groq.com/openai/v1
    api-key: ${GROQ_API_KEY:}
    model: llama-3.3-70b-versatile
    connect-timeout: 5s
    read-timeout: 20s
  fallback:
    enabled: ${LLM_FALLBACK_ENABLED:true}
    response: "[降级响应] AI 渠道暂不可用，本次结果未经模型处理，请稍后重试。"
```

环境变量一览：

| 变量 | 作用 | 缺省行为 |
|---|---|---|
| `GEMINI_API_KEY` | spring.ai 与 llm.gemini **共用同一 Key** | 未设 → gemini 渠道跳过 |
| `GROQ_API_KEY` | llm.groq | 未设 → groq 渠道跳过 |
| `OCRSPACE_API_KEY` | vision.ocr.ocrspace | 未设 → 用 yml 内置免费 Key |
| `AZURE_OCR_ENABLED` / `_ENDPOINT` / `_API_KEY` | vision.ocr.azure | 默认关闭 |
| `LLM_GEMINI_ENABLED` / `LLM_GROQ_ENABLED` / `LLM_FALLBACK_ENABLED` | 渠道开关 | 均默认 true |

健康检查规则：`enabled=false` 或缺少 Key/baseUrl 的渠道启动后被调度器跳过（INFO 日志可见），不影响其余渠道。

---

## 7. 降级行为一览

| 场景 | 结果 |
|---|---|
| Azure 429 | warn 日志 → 流转 OCR.space（可重试，max-attempts=2） |
| OCR 全渠道失败 | 503「所有 OCR 渠道均不可用：azure(...)；ocrspace(...)」 |
| OCR 成功但图中无文字 | 422 空文本拦截（不进 LLM） |
| Gemini 429（RPM 窗口） | warn 日志 → 流转 Groq（默认不重试） |
| Gemini + Groq 全败 | fallback 哑响应：`[降级响应]` 开头模板，调用方可识别 |
| fallback 关闭且全败 | 503「所有 LLM 渠道均不可用」 |
| Prompt 空白 | 400 |

---

## 8. 扩展新渠道指南

以新增 OpenAI 兼容渠道（如硅基流动/智谱）为例：

1. 新建 `llm/service/impl/XxxLlmService extends AbstractOpenAiCompatibleLlmService`，构造器传入渠道名、`LlmProperties` 新增的 Provider 配置块、`ObjectMapper`；
2. 类上 `@Component` + `@Order(n)` 插入优先级（数字越小越优先）；
3. `LlmProperties` 增加 `private final Provider xxx = new Provider();`；
4. `application.yml` 增加对应配置块（Key 用环境变量占位符）；
5. 测试：按 `OpenAiCompatibleLlmServiceTest` 复制 HttpServer 桩用例（429/5xx/401/非 JSON），`LlmChainRouterTest` 补充新渠道顺序用例。

非 OpenAI 兼容协议（如原生 SDK）则直接实现 `LlmService` 三方法，其余步骤相同。

---

## 9. 测试与验证

| 测试类 | 用例 | 覆盖点 |
|---|---|---|
| `OcrChainManagerTest` | 9 | OCR 链：优先级/可重试流转/不可重试/健康跳过/空结果/缓存命中/全败 503 |
| `AzureOcrServiceExtractContentTest` | 9 | extractContent 纯解析：4.x/v3.2 各代 content 路径/blocks 逐行兑底/生产实测回归（无 content 字段）/真·空图不告警/结构未命中 WARN/error 体分类 |
| `LlmChainRouterTest` | 10 | LLM 链：优先级/429 快速流转/可配置重试/兑底/全败 503/空 Prompt/降级模板识别 |
| `LlmChannelWiringTest` | 1 | llm 渠道真实装配（ApplicationContextRunner 最小上下文，防嵌套配置类被误当独立 bean 注入） |
| `OpenAiCompatibleLlmServiceTest` | 8 | JDK HttpServer 本地桩（真实 HTTP 往返）：Bearer 鉴权/内容解析/429/5xx/401/非 JSON/缺 choices/读超时 |
| `PromptFormatterTest` | 9 | 清洗规则与模板（通用 + 交易提取/审查模式） |
| `ImageTextProcessingFacadeTest` | 9 | 编排顺序/空文本拦截/异常透传/默认指令/结果缓存命中/强制刷新审查模式/降级不缓存/解析失败不缓存 |
| `TradeDraftParserTest` | 5 | 围栏清理/二维数组映射/脏行隔离/非 JSON 500/空结果语义 |
| `ModulithVerifyTest` | 2 | 模块边界（vision→llm 基包单向依赖） |

```sh
./mvnw compile -q
./mvnw test -q '-Dtest=AzureOcrServiceExtractContentTest,OcrChainManagerTest,LlmChainRouterTest,LlmChannelWiringTest,OpenAiCompatibleLlmServiceTest,PromptFormatterTest,ImageTextProcessingFacadeTest,TradeDraftParserTest,ModulithVerifyTest' '-DfailIfNoTests=false'
```

以上 62 用例全部通过（2026-09-01 单轮验证）；`OpenAiCompatibleLlmServiceTest` 为本地桩的真实 HTTP 测试，未打真实外部 API。

---

## 10. 日志观测

| 时机 | 日志样例 |
|---|---|
| 启动装配 | `OCR 责任链装配完成，渠道优先级：[azure, ocrspace, local-gemini]` / `LLM 责任链装配完成，渠道优先级：[gemini, groq, fallback]` |
| 渠道成功 | `OCR 识别成功 (channel=.., hash=.., cost=..ms, textLength=..)` / `LLM 调用成功 (provider=.., cost=..ms, ..)` |
| 渠道失败 | `OCR 渠道失败，流转下一渠道 (channel=.., attempt=1/2, retryable=.., reason=..)` |
| 缓存命中 | `OCR 文本缓存命中，跳过渠道调用 (hash=..)` |
| 全链完成 | `图片→AI 全链路完成 (ocrCost=.., formatCost=.., llmCost=.., total=..)` |
| 全链失败 | `全部 OCR/LLM 渠道均失败 (failures=[..])` → 503 |

---

## 11. 已知限制与注意事项

1. **Groq 模型轮换**：免费层可用模型会下线，`llm.groq.model` 保持配置化，失效时改 yml；
2. **双 Gemini 额度**：OCR 兜底与 LLM 首选同为 Gemini，极端场景（azure+ocrspace 双挂）同一图会调 Gemini 两次（提文本 + 处理文本）——发生率低，P2 可加门面级 OCR 兜底开关；
3. **降级可识别**：全链降级结果以 `[降级响应]` 前缀标识，前端可按前缀提示用户；
4. **明文 Key**：`OCRSPACE_API_KEY` 默认值仍内置 yml（开箱可用），仓库公开前建议改为空默认值 + 纯环境变量；
5. **LLM 无结果缓存**：同图同任务重复请求会重复消耗免费额度（P2 预留）；
6. **最坏耗时**：约等于 Σ(启用渠道数 × connect+read 超时 × max-attempts) + 退避 + Azure 轮询，个人场景低概率触顶；前端/网关超时建议 ≥90s，或按需调低各渠道 read-timeout；
7. **网络代理（生产实测）**：本机直连 `generativelanguage.googleapis.com` 不通（connect timeout），必须走代理——JVM 需真实系统属性 `-Dhttps.proxyHost=... -Dhttps.proxyPort=...`（IDEA 里放 **VM options**，放 Program arguments 无效，`HttpClient` 经 `ProxySelector.getDefault()` 读取）；代理故障时 Gemini 免费层偶发 503 high demand，重试可过；`api.groq.com` 与 global Azure 端点直连可达；
8. **验证口径**：2026-09-01 已完成真实端到端冒烟——真实交易截图 `/api/import/process-image?useCache=false`：azure OCR（blocks 兜底路径提取 656 字符）→ gemini（走代理，16936ms）→ 解析 9 笔交易草稿全部正确；`/api/import/ocr-text` 同图命中修复后返回完整文本；Gemini/Groq/OCR.space 真实端点均已在真实链路中验证。
