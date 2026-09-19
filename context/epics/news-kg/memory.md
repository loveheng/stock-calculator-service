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

## 断点

- [断点] 下一步：断点二——crawler 基包汇编/字典查询 API + main kg 域骨架（KgExtractTask handler、KgExtractPublisher、KgResultService、KgFuseService、entity×6、repository×6）+ application.yml kg.* 配置 + monitor 队列巡检接入
