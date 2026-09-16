---
status: active
updated: 2026-09-15
---

# 文档索引

按功能域组织。功能域内文档为该功能的生命周期切片（spec → design → implementation → api），其中 `api.md` 是前端对接入口。

> 规范：新增/修改文档遵循 `stock-calculator-docs` skill（落点/命名/Frontmatter/引用/废弃）。每篇头部 frontmatter 标注 `status` / `updated`——时效以文件头为准，本索引不重复（防双源漂移）。

## copilot/ — Context-Aware Copilot（AI 聊天）

- [spec](copilot/spec.md) · 设计决策规范
- [design](copilot/design.md) · 设计方案
- [implementation](copilot/implementation.md) · 开发实施记录
- [api](copilot/api.md) · 接口文档 v1.0（前端对接）

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

## ai-pipeline/ — AI 管道（三条独立管道）

- [announcement-rag](ai-pipeline/announcement-rag.md) · 公告提取与蒸馏管道（announcement 域）设计
- [cls-article-vector](ai-pipeline/cls-article-vector.md) · cls_article 向量化（语义检索基座）设计
- [ocr-llm](ai-pipeline/ocr-llm.md) · 多渠道 OCR + 免费 LLM 全链路管道

## architecture/ — 架构与模块拆分

- [data-service-split](architecture/data-service-split.md) · 数据服务拆分与 MQ 通信设计
- [pull-loop-unification](architecture/pull-loop-unification.md) · 数据拉取自循环化设计（已实施 2026-09-13）
- [module-split-test-plan](architecture/module-split-test-plan.md) · 主/数据模块拆分测试计划
- [data-source-onboarding](architecture/data-source-onboarding.md) · 新数据源接入指南（webhook ingest · 阶段 5）
- [agent-skill-system](architecture/agent-skill-system.md) · AI 辅助开发 skill 体系设计意图与使用手册

## deploy/ — 部署与运维

- [cloud-run-data](deploy/cloud-run-data.md) · Cloud Run data 副本部署手册（公网 AMQP 直连）
- [data-worker-replica](deploy/data-worker-replica.md) · Data 副本部署手册（单镜像任意副本）
- [vm-migration-2026-09-14](deploy/vm-migration-2026-09-14.md) · 跨机迁移与部署收口实录（2026-09-14）
