---
status: active
updated: 2026-09-20
---

# 文档索引

按功能域组织。功能域内文档为该功能的生命周期切片（spec → design → implementation → api），其中 `api.md` 是前端对接入口。

> 规范：新增/修改文档遵循 `stock-calculator-docs` skill（落点/命名/Frontmatter/引用/废弃）。每篇头部 frontmatter 标注 `status` / `updated`——时效以文件头为准，本索引不重复（防双源漂移）。

## copilot/ — Context-Aware Copilot（AI 聊天）

- [spec](copilot/spec.md) · 设计决策规范
- [design](copilot/design.md) · 设计方案
- [implementation](copilot/implementation.md) · 开发实施记录
- [api](copilot/api.md) · 接口文档 v1.0（前端对接）
- [memory-profile](copilot/memory-profile.md) · 记忆固化与用户画像抽取设计（独立主题，评审稿）
- [memory-summary](copilot/memory-summary.md) · 记忆系统速记（延迟队列提炼 + 画像抽取要点速查）

## e2ee-auth/ — E2EE 用户服务

- [design](e2ee-auth/design.md) · 后端设计方案
- [implementation](e2ee-auth/implementation.md) · 后端实行方案
- [api](e2ee-auth/api.md) · 接口文档 v1.0（前端对接）

## server-sync/ — 服务端密文同步（登录即备份）

- [design](server-sync/design.md) · 后端设计文档
- [implementation](server-sync/implementation.md) · 后端开发实施

## custom-stats/ — 自定义统计（AI 生成代码）

- [support](custom-stats/support.md) · 后端开发信息支持
- [implementation](custom-stats/implementation.md) · 后端实现文档
- [api](custom-stats/api.md) · 接口文档 v1.0（前端对接）

## news-search/ — 资讯搜索

- [implementation](news-search/implementation.md) · 后端技术实现（Spring Boot :18080）
- [api](news-search/api.md) · 接口文档（后端开发对接）

## ai-pipeline/ — AI 管道（四条独立管道）

- [announcement-rag](ai-pipeline/announcement-rag.md) · 公告提取与蒸馏管道（announcement 域）设计
- [cls-article-vector](ai-pipeline/cls-article-vector.md) · cls_article 向量化（语义检索基座）设计
- [cls-news-kg](ai-pipeline/cls-news-kg.md) · 《新闻联播》要闻时序知识图谱（kg 域）设计
- [kg-api](ai-pipeline/kg-api.md) · kg 时间轴查询 API 前端对接文档（信封/字段表/样例/待确认清单）
- [ocr-llm](ai-pipeline/ocr-llm.md) · 多渠道 OCR + 免费 LLM 全链路管道

## notify/ — 个人定制提醒服务（stock-calculator-mcp-notify）

- [design](notify/design.md) · 通知者服务设计（MCP 登记/触发引擎/能力请求/触达，TTL+DLX 自循环，评审稿）
- [web-push](notify/web-push.md) · Web Push 推送通知双通道（VAPID 推送 + 消息落库拉取兜底，订阅/消息接口/部署配置）

## mcp/ — MCP 服务（指标计算 + 书籍知识检索）

- [design](mcp/design.md) · stock-calculator-mcp 模块设计（独立模块/独立库/Redis 字典镜像/bge-m3 复用）
- [implementation](mcp/implementation.md) · 实现规划（M1 骨架建库 → M2 字典镜像 → M3 calc → M4 kb RAG）
- [usage](mcp/usage.md) · 使用手册（启停/客户端接入/六工具速查/订阅源管理/加书/管理口/FAQ）

## architecture/ — 架构与模块拆分

- [data-service-split](architecture/data-service-split.md) · 数据服务拆分与 MQ 通信设计
- [pull-loop-unification](architecture/pull-loop-unification.md) · 数据拉取自循环化设计（已实施 2026-09-13）
- [module-split-test-plan](architecture/module-split-test-plan.md) · 主/数据模块拆分测试计划
- [data-source-onboarding](architecture/data-source-onboarding.md) · 新数据源接入指南（webhook ingest · 阶段 5）
- [agent-skill-system](architecture/agent-skill-system.md) · AI 辅助开发 skill 体系设计意图与使用手册
- [agent-orchestration](architecture/agent-orchestration.md) · Agent 任务编排系统设计（LLM 规划 DAG + 确定性执行 + 意图复用，评审稿）
- [agent-orchestration-implementation](architecture/agent-orchestration-implementation.md) · Agent 编排实现技术文档（代码落点 / 执行流程 / 链路边界口径 / 进度）
- [agent-orchestration-possibilities](architecture/agent-orchestration-possibilities.md) · Agent 编排的可能性功能展望与演进路线

## deploy/ — 部署与运维

- [cloud-run-data](deploy/cloud-run-data.md) · Cloud Run data 副本部署手册（公网 AMQP 直连）
- [data-worker-replica](deploy/data-worker-replica.md) · Data 副本部署手册（单镜像任意副本）
- [vm-migration-2026-09-14](deploy/vm-migration-2026-09-14.md) · 跨机迁移与部署收口实录（2026-09-14）
