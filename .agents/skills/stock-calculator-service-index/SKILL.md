---
name: stock-calculator-service-index
description: stock-calculator-service（Maven 模块 contract/main/data，本表覆盖 main；data/contract 见 Base 包小节）的「功能 → 代码落点 + 文档落点」归属索引。修改、新增、排查、评审功能前，先用本表定位领域包与 docs 域，再用行内命令展开该域类清单，避免全仓扫描浪费 token。机制与维护协议见 project-index。
---

# stock-calculator-service 功能索引

机制/格式/维护协议见全局 skill `project-index`；本文件是项目数据，随仓库演进。

## 用法

1. 按归属表（或关键词映射）定位领域。
2. 复制该行展开命令在 terminal 直接跑（全部为无变量的字面量命令），或用 grep 工具以该域路径做 include_pattern 限定搜索。
3. 展开得到的类清单**只用于当前任务，禁止写回本索引**——表里只存大类与子包名，防止索引腐化。
4. 表中定位不到时，才对整个 base 包做全量 grep。

## 模块与 Base 包

- main Base 包：`stock-calculator-main/src/main/java/com/zzh/stock_calculator/`
- 测试目录与主源同域镜像：`stock-calculator-main/src/test/java/com/zzh/stock_calculator/`
- data 模块 Base 包：`stock-calculator-data/src/main/java/com/zzh/stock_calculator/data/`（MQ 拉取循环 + 公告/向量化 worker；子包 announcement/cls/config/hello/ingest/llm/mq/worker）
- contract 模块：`stock-calculator-contract/src/main/java/com/zzh/stockcalc/contract/`（MqKey/MqQueue/MessageType/MqExchange 等契约常量，消息 payload 在 message/ 子包；包名无下划线）

## 归属表

| 领域 | 覆盖功能 | 代码落点（子包 · 展开命令） | 文档落点 |
|---|---|---|---|
| auth | 注册/登录/会话/OTP 验证码/E2EE 公钥档案/邮件/限流 | 基包(AuthErrorCode) · config · controller · dto · entity · repository · service · util — `find stock-calculator-main/src/main/java/com/zzh/stock_calculator/auth -name '*.java'` | docs/e2ee-auth/ |
| crawler | 财联社电报抓取(sign/roll)/题材/股票字典/文章入库/历史同步/定时任务/事件 | 基包(*Api) · controller · embedding · entity · event · mq · repository · service · task — `find stock-calculator-main/src/main/java/com/zzh/stock_calculator/crawler -name '*.java'` | docs/ai-pipeline/（cls-article-vector） |
| vision | 截图 OCR/Gemini 识别/交易草稿(TradeDraft)/图片预处理 | 基包(OcrExecutor) · controller · dto · enums · impl · service · service/impl · util — `find stock-calculator-main/src/main/java/com/zzh/stock_calculator/vision -name '*.java'` | docs/ai-pipeline/（ocr-llm） |
| copilot | AI 聊天（DeepSeek）：会话/消息/流式问答(SSE)/提示词模板管理 | 基包(CopilotPromptResolver) · config · controller · dto · entity · repository · service · service/store · util — `find stock-calculator-main/src/main/java/com/zzh/stock_calculator/copilot -name '*.java'` | docs/copilot/ |
| llm | LLM 提供方路由：Gemini/Groq/DeepSeek/OpenAI 兼容 + fallback 链 | 基包(LlmChainRouter) · config · service · service/impl — `find stock-calculator-main/src/main/java/com/zzh/stock_calculator/llm -name '*.java'` | — |
| sync | 服务端密文同步（登录即备份）：快照 CAS 上传/拉取/meta 对账/频控/历史 | controller · dto · entity · repository · service — `find stock-calculator-main/src/main/java/com/zzh/stock_calculator/sync -name '*.java'` | docs/server-sync/ |
| customstat | 用户自定义统计（自选统计定义 CRUD：/api/custom-stats） | controller · dto · entity · repository · service — `find stock-calculator-main/src/main/java/com/zzh/stock_calculator/customstat -name '*.java'` | docs/custom-stats/ |
| announcement | 公告订阅/处理任务下发与结果对账/内容溯源/向量化二段下发 | 基包(AnnouncementQueryApi) · config · controller · dto · entity · event · mq · repository · service · task — `find stock-calculator-main/src/main/java/com/zzh/stock_calculator/announcement -name '*.java'` | docs/ai-pipeline/（announcement-rag） |
| kg | 《新闻联播》要闻时序知识图谱：任务发布（最新 3 条未处理扫描）/结果摄取（证据先行+DONE 判重）/字典锚点融合（实体/关系/事件时间线） | 基包 · config · entity · mq · repository · service · task — `find stock-calculator-main/src/main/java/com/zzh/stock_calculator/kg -name '*.java'` | docs/ai-pipeline/（cls-news-kg） |
| monitor | 拉取循环心跳记录/看门狗/管道巡检告警（pull_heartbeat） | 基包(PullLoopWatchdogTask 等) · entity · repository — `find stock-calculator-main/src/main/java/com/zzh/stock_calculator/monitor -name '*.java'` | — |
| search | 资讯搜索（news-search，:18080） | config · controller · dto · service · task · util — `find stock-calculator-main/src/main/java/com/zzh/stock_calculator/search -name '*.java'` | docs/news-search/ |
| common | 统一响应/全局异常 | 基包，无子包 — `find stock-calculator-main/src/main/java/com/zzh/stock_calculator/common -name '*.java'` | — |
| config · util · 根 | RestClient 配置 / HttpUtil / 应用入口 | 固定 3 文件，直接引用：`config/RestClientConfig.java`、`util/HttpUtil.java`、`StockCalculatorApplication.java`（相对 base 包） | — |

跨域架构与部署运维文档不挂功能域：docs/architecture/（模块拆分/拉取循环/数据源接入）、docs/deploy/（部署手册/实录）。

## 业务别名映射（口语/别名/关键词 → 域）

- **auth**：登录、注册、OTP、验证码、会话、token、公钥/私钥、E2EE、档案、邮件、限流、BCrypt、拦截器、鉴权、忘记密码、重置密码
- **crawler**：cls、财联社、电报、题材、subject、股票、stock、字典、sign、roll、抓取、爬虫、日线、补录、历史、同步、定时、event、ApplicationEvent
- **vision**：截图、OCR、Gemini、vision、识别、草稿、TradeDraft、trade、ocr-parse、图片、预处理、ImagePreprocess、OcrExecutor、缓存、交易截图、截图导入
- **copilot**：copilot、AI 聊天、AI 对话、DeepSeek、会话、消息、SSE、流式、提示词模板
- **llm**：llm、LLM、大模型、Groq、provider、模型路由、模型切换、fallback、OpenAI 兼容
- **kg**：知识图谱、新闻联播、联播要闻、时序图谱、实体、三元组、关系边、事件时间线、证据、融合、锚点、kg
- **sync**：同步、备份、backup、快照、密文、信封、envelope、CAS、版本冲突、频控、user_sync、云备份、登录即备份
- **common**：ApiResponse、BusinessException、GlobalExceptionHandler、异常处理、统一响应
- **customstat**：自定义统计、custom-stats、统计定义、UserCustomStat
- **announcement**：公告、announcement、订阅、快照、溯源、AnnouncementQueryApi
- **monitor**：心跳、heartbeat、看门狗、watchdog、巡检、pull_heartbeat、PullLoop
- **search**：搜索、search、资讯、news
- **config · util · 根**：RestClient、HttpUtil、Application、启动类

## 变更落点顺序

1. 接口行为：`controller` → `service` → `repository`/`entity` → `dto`
2. 新增配置键：main 模块 `src/main/resources/application*.yml`
3. 表结构：仓库根 `postgres/schema.sql`、`postgres/data.sql`
4. 定时任务：crawler/task；领域事件：crawler/event
5. Modulith 边界：跨域只能引用对方**基包**公开类型（ModulithVerifyTest 守护）；vision 的 `OcrExecutor` 留在基包是 @Cacheable 缓存边界，勿内联进 service（self-invocation 会让缓存失效）

## 验证

```
./mvnw test -pl stock-calculator-main -am '-Dtest=!StockCalculatorApplicationTests,!SyncBackupL1IntegrationTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-DfailIfNoTests=false'
```

两个 @SpringBootTest（contextLoads / SyncBackupL1）需本地 PG，无 DB 环境排除。口令与容器信息见 stock-calculator-workflow。

## 命令速查（跨 skill 指针）

| 场景 | 去处 |
|---|---|
| main/data 模块单测（无 DB 排除项）与 E2E / 本地环境门控（RABBIT_E2E、POSTGRES_PASS） | stock-calculator-workflow「构建命令」 |
| docs 自检（docs-index-lint.sh） | stock-calculator-docs §八 |
| Native 编译 / 冒烟（build-native.sh、smoke-curl.sh；前置 install 见 native-build §一） | stock-calculator-native-build §五 |

index-lint.sh 见下方「维护约定」。

## 维护约定

- 新增领域/大功能 → 归属表加一行；**禁止把类名清单写进来**，只允许子包名与固定单文件例外。
- 域结构变更后跑 `sh scripts/agent-tools/index-lint.sh`（归属表↔Base 包目录双向校验：防孤儿域、防断链；已入 toolbox 项目池，也可 `toolbox run-hooks`/直接调用）。
- 新增 docs 域 → 三处同步：本表文档落点列、stock-calculator-docs §〇 域表、docs/README.md 域头。
- 命名即定位：路径规律为 `领域/<层>/<类名>.java`，先按规律猜，再用上表命令或 find_path 验证。
- 关联 skill：stock-calculator-backend-dev（后端 DTO/Entity/Service 等写法规范）、stock-calculator-workflow（终端与文件写入限制、本地库口令）、stock-calculator-frontend-dev（前端项目）、stock-calculator-native-build（native 构建与运行期元数据）。
