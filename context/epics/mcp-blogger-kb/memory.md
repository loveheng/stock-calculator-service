---
dev-loop: memory
format: v1
epic: mcp-blogger-kb
total-merged: 0
last-merge: none
---

# mcp-blogger-kb：博主观点知识库 + 人格提炼（数字人前菜）

> 在 mcp-service 的 kb 体系上扩展：经典书籍（确定性知识）之外接入财经博主内容作为
> 观点层（非确定性知识），并从博主语料离线提炼人格卡供数字人客户端做人设。
> 前置档案：context/archive/mcp-service/memory.md（kb 管线/工具契约/D1-D10 决策）。

## 核心模型（2026-09-20 会话定案）

- 三层知识分型：书 = 确定性知识（查证用）；博主 = 非确定性观点（参考用）；人格 = 从观点层蒸馏的统计特征（语气/句式/立场），天然抗噪、比单条观点稳定。
- 观点的不可靠性靠三层元数据消化而非清洗：出处（博主 ID）、时间（published_at，行情判断类检索偏新近）、类型（方法论 vs 行情判断——方法论老得慢，行情判断易馊）。
- 风格不是知识不进检索层：persona 属客户端提示词层，MCP 只提供 kb_persona 工具吐卡片+金句；数字人大脑口径 = 事实引书、观点标博主、语气按 persona 卡。
- 冲突当特性：博主互打、博主与书打架，由数字人摆三方观点，是「有意思且值得借鉴」的来源。

## 数据管线（存量 txt + 增量 RSS）

- 存量：txt 一次灌（省掉 EPUB 抽取环节），与增量共用「观点单元切块 → bge-m3 embedding」管线；切块单位是观点单元而非固定 600/80 字（口语冗余大，固定切块会碎）。
- 增量：RSS 轮询拉模式（默认 6h，kb.rss.* 门控逐源 fail-open），content_hash 增量去重、不建状态机；平台适配推给 RSSHub。解析用 JDK DOM（KbTextExtractor 同款零依赖套路，原拟 Rome 按零新依赖+标准实现优先改定）；RSS 2.0/Atom 通吃：全文型取 content:encoded、摘要型取 description、标题型（description 复读标题）去重后块=标题+链接（政策雷达形态，正文抓取后续评估）；视频/播客型需 ASR 留 v2。
- 存储：每博主 = 一本伪书（kb_book category=blogger，一期内容 = chapter_path）；kb_chunk 加 published_at 列（照 quote_daily ALTER IF NOT EXISTS 老路）；量大后再拆独立 opinion 表 + 主题标签（术语卡同款「先兜住再说」）。

## 订阅源管理（2026-09-20 用户定案）

- 源注册表模型：源 = 名字（博主名，唯一）+ 类型（text | rss）+ 位置（txt 文件路径 | RSS URL）+ 状态（active | removed）；落表管理，增/删/列表走 /admin/source REST 口（照 resync、dict refresh 先例），MCP 工具层不暴露管理动作（管理是人干的事，不是 LLM 的）。
- 新增 text 源：给名字 + 文本文件即触发灌入（观点单元切块 + embedding，content_hash 幂等）；新增 rss 源：给名字 + URL 即首拉全量 + 进轮询。
- 移除默认停更保数据：status=removed，轮询跳过，kb_search / kb_persona 检索侧过滤 removed 源（可逆、防误删）；物理清除（伪书 truncate 同款机制）做独立动作不绑在移除上。
- v1 简化：一个名字 = 一个源 = 一本伪书；同博主多源（博客 RSS + txt 存档并存）需要合书时再说。

## 人格提炼

- 离线管线：博主全量 chunk → LLM 提炼 persona 卡（只抽风格层：语气/比喻/立场/句式，严禁把具体行情判断固化进人格）+ 10-20 段金句 few-shot；卡片存伪书（category=persona），带生成日期+模型留档（照 embedding_model 纪律），风格漂移重跑覆盖。
- 新工具 kb_persona(blogger)：粗粒度返回 persona 卡 + 金句，客户端拼 system prompt；「多博主合成一个人格」= 每博主一张卡 + 离线融合跑一次 LLM，同机制双玩法。

## 分期（待博主源确认后细化）

- M1 订阅源管理（/admin/source 增/删/列表）+ text 灌入 + RSS 首拉与轮询 + published_at 补列；M2 persona 提炼管线 + kb_persona 工具；M3 数字人客户端接入调通。

## 进度

- M1-text（2026-09-20 完成）：kb_source 注册表 + /admin/source（注册即灌入 / 移除停更保数据 / 清单）+
  微博备份解析（59 条→55 chunk，纯媒体剔除，一条=一个观点单元）+ 博主伪书（category=blogger，
  source_id 关联，published_at 补列）。E2E：validate 对账通过、55 块全带时间（09-06→09-20）、
  删后块保留、重注册重灌 55/55、MCP kb_search 真调命中「麻辣新鲜/微博 2026-09-06 06:45」出处与书籍同榜。
  单测 33/33。样例源：麻辣新鲜 = /home/zzh/Documents/blog/2188093987.txt（微博备份导出格式）。
- M1b（2026-09-20 完成）：RSS 拉取/解析/增量全链路——KbRssFeedParser（JDK DOM，RSS2.0/Atom，RFC-822/ISO
  时间双容错折算系统时区）+ KbRssClient + KbRssPoller（6h 轮询逐源 fail-open）+
  /admin/source/{name}/refresh 手动刷新 + ingestSourceRss（hash 去重只补新，chunk_index 续排，
  chapter_path=条目标题）。E2E：注册「政策法规」（PolitePaul 桥接 gov.cn 标题型 feed）首拉 11/11、
  refresh 二刷 0 新 11 跳、11 块全带 published_at（+08 折算正确）、MCP kb_search「房车消费」命中
  政策条目带链接出处。单测 38/38。
- 运维注意：mcp 以旧构建常驻时占 18081，重启用新构建前先杀旧 java 实例。
- M2（2026-09-21 完成）：persona 提炼管线 + kb_persona 工具——KbLlmClient（OpenAI 兼容原生
  REST，复用 DEEPSEEK_* 三键，HTTP/1.1 显式工厂 + byte[] 收包 UTF-8 自解码 + max_tokens 8192 +
  finish_reason=length 显式检测 + 解析失败落「长度+头部」诊断日志；踩坑见 lessons）+
  KbPersonaService 单次调用抽风格卡（summary/tone/metaphor/stance/syntax，字段代码侧钳 300 字）
  + 原文金句（10-20 条 ≤100 字提示词约束，代码侧钳 20 条/150 字）→ persona 伪书覆盖写
  （kb_book 补 persona_model/persona_generated_at 留档）+ POST /admin/source/{name}/persona。
  persona 不进检索层：kb_search 关键词路显式排除（向量路无 embedding 天然不可命中）+
  kb_book_list 排除。E2E（模型 step-3.7-flash）：麻辣新鲜真跑 卡+18 金句，MCP 真调
  tools/list（7 工具）/kb_persona/kb_book_list/kb_search 全通过。单测 47/47。
  注意：新 MCP 工具必须同步挂 McpToolConfig.toolObjects（显式列举注册，见 lessons）。

## 断点

- [断点] 下一步：M3 数字人客户端接入调通（M2 已收口；提炼当前模型 = .env 的 step-3.7-flash，风格漂移重跑 /admin/source/{name}/persona 即可）
