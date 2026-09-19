---
dev-loop: memory
format: v1
epic: news-kg
total-merged: 0
last-merge: none
---

# news-kg：财联社《新闻联播》要闻 → 时序知识图谱

> 用 cls_article 中《新闻联播》要闻汇编（title 命中「《新闻联播》要闻」，每天 1 条）构建 PG 时序知识图谱。
> 管线复用 announcement 模式：main 发布任务 → data worker LLM 抽取 → result 上行 → main 证据落库 + 融合。

## 已定决策（2026-09-19 评估定案）

- 图存储：PostgreSQL 建模（kg_entity/kg_relation/kg_event + 证据表），不引入 Neo4j（native 构建与运维负担）。
- 落库两段式：main 先落证据行（content_hash 判重），再融合进图谱；融合失败不回退任务状态，证据可重放。
- data 守无状态抽取分界：实体消解/融合只在 main；worker 单篇抽取走 Spring AI（2.0.1）structured output。
- 数据源：cls_article title LIKE '%《新闻联播》要闻%'（content 匹配有 24 篇噪音，弃用）；level 全 B，正文 600-760 字，每天 1 条，12:00-14:00 发布。
- 调度：pull_task_config CALENDAR 行（凌晨 Asia/Shanghai），发布器扫描「最新 3 条未处理」逐条下发；代码零定时器。
- 实体锚点：优先挂 stock/cls_subject 字典，字典外实体进候选池。
- LLM 错误三分类：解析失败 PERMANENT / 网络 TRANSIENT 留 PENDING 对账重发（仿 EmbeddingErrorClassifier）。
- 事件时间归一化：以正文绝对日期为准，相对表述基于文章 ctime；标题日期与联播播出日可能差一天。
- 历史回填（二期首项，2026-09-19 落地）：独立 job.kg.backfill（每 30min）最旧优先 ASC 分批补发，复用 task.kg.extract 队列/结果通道/熔断，无独立状态机；kg.backfill.batch-size=20 / scan-multiplier=3 / startup-delay=15s，enabled 总开关（E2E 必关）；状态行下发时才建，不预播种（保 PENDING_AGE 语义）。
- 前端可视化定案（2026-09-19）：搜索驱动 + 竖向时间轴合体——时间轴卡片流为唯一渲染形态，搜索（关键词/实体）为入口；空态 = 最近时间轴 + 实体热榜 chips（用户拍板不展示补录进度）；API 四端点 /api/kg/timeline（搜索与浏览共用，日分页 page 0 起）/entities/suggest/entities/hot/entities/{id}，前端对接文档 docs/ai-pipeline/kg-api.md（draft 待前端确认），鉴权挂 AuthInterceptor（/api/kg/**）；keyword 命中口径=事件文本 OR 关联实体名/别名（jsonb::text ILIKE）；时区约定 event_time 落库与展示同用 systemDefault。

## 断点

- [断点] 下一步：重建重启主服务（同时拿到 hot 端点映射与 42P18 CAST 修复）→ 前端四端点联调；回填进行中（事件已至 2023-10）；遗留：FAILED PERMANENT 抽查、窗口期 null event_time 回填 SQL 可重跑
