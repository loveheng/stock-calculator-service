---
name: stock-calculator-docs
description: stock-calculator-service 的 docs/ 工程文档规范：功能域子目录落点与命名、Frontmatter（status/updated）时效标注、Mermaid 图表标准、废弃 Tombstone、文档引用维护与移动时全仓引用修复、README 索引同步、写后自检 lint，以及代码改动触达对外契约或域核心流程后的文档联动提示（建议级）。新增/修改/移动/废弃 docs 文档（写完跑 §八 自检 lint）、查找文档落点、判断文档时效、代码变更后需提示文档联动时使用。
---

# stock-calculator-service · docs/ 文档规范

管辖范围：仓库 `docs/` 下全部工程文档。过程记忆（`context/`、memory/devlog/lessons）不适用本规范，归 dev-loop。

> **机制/数据分层**：通用规范机制（frontmatter 时效、落点与切片命名、Mermaid、Tombstone、引用移动与索引、写后自检口径）见全局 `docs-spec` §1–§7——**本 skill 只含本项目数据**（域表、`docs-index-lint.sh` 路径、本仓例外），严禁复制规范正文（防双源）。

## 〇、结构与落点

docs/ 按功能域切分子目录，新文档严禁在根目录平铺（README.md 除外）：

| 域 | 定位 |
|---|---|
| copilot/ | Context-Aware Copilot（AI 聊天） |
| e2ee-auth/ | E2EE 用户认证 |
| server-sync/ | 服务端密文同步 |
| custom-stats/ | 自定义统计（AI 生成代码） |
| news-search/ | 资讯搜索 |
| guide/ | 选股引导（消息→候选股→个股档案两步向导） |
| ai-pipeline/ | AI 管道（公告 RAG / CLS 向量 / OCR-LLM） |
| mcp/ | MCP 服务（股票指标计算 + 经典书籍知识检索，独立模块） |
| architecture/ | 跨域架构决策（模块拆分、拉取循环、数据源接入、测试计划） |
| deploy/ | 部署与运维手册/实录 |

规则：
- 新文档先查项目索引 skill `stock-calculator-service-index` 定位功能域，落 `docs/<域>/`；跨域架构 → architecture/；部署运维 → deploy/。
- 域内生命周期切片固定名：`spec → design → implementation → api → support`；独立主题用主题名（如 `pull-loop-unification.md`）；迁移/部署实录保留日期后缀（如 `vm-migration-2026-09-14.md`）；文件名一律 kebab-case。
- 新增或整域变迁（拆分/合并，见 §四）时三处同步：本节域表、`docs/README.md` 域头、`stock-calculator-service-index` 归属表文档落点列（防双源漂移）。
- docs/ 与 context/ 边界：docs = 跨会话工程交付物（设计/接口/部署手册）；context = 过程记忆（memory/devlog/lessons）。过程记忆严禁写入 docs/。

## 一、Frontmatter（强制）

docs/ 下所有 .md（含 README.md 索引）头部必须有且仅有两个字段：

```yaml
---
status: active
updated: 2026-09-15
---
```

- `status`：draft | active | deprecated；`updated`：YYYY-MM-DD（最后一次实质修改日期）。
- 不扩展其他字段（不加 author/version/created/date）。
- 实质修改正文必须同轮刷新 updated；事实性修正（纠正错误的表述/数字/结论）属实质修改，必须刷新；typo/排版、纯引用路径修复等基础设施性改动不刷新。
- 读到 `status: deprecated` 的文档：严禁当作现行事实引用，必须按正文墓碑行指向的继任文档。

## 二、反向触发（建议级）

代码改动收尾时，若本轮触达以下任一条件，回合末尾附注一行提示：
- 对外契约变更（机械可判）：Controller 的 @XxxMapping 端点（新增/改路径/改出入参）、请求响应 DTO 字段、错误码定义 → 提示核对 `docs/<域>/api.md`
- 域核心流程变更（锚点可判，触达任一即算）：Service 层公共方法签名/行为、MQ topic 与消息结构、定时任务入口、领域包间调用边界 → 提示核对 design / implementation

模板：`> ⚠️ [文档联动] 本轮改动了 <域> 的对外契约/核心流程，建议核对 docs/<域>/<切片>.md`

仅提示，由用户确认后手动执行，不擅自改文档。

## 三、Mermaid 唯一图表标准

凡流程、状态机、架构图、时序的描述，强制使用 Mermaid 代码块，严禁 ASCII 画图；存量 ASCII 图随域文档实质修改时顺手改造。

前提假设（ADR）：文档消费环境为 Zed / 现代 Git 平台（GitHub、新版 GitLab），Mermaid 原生渲染，故不设图表类型白名单。若未来出现终端纯文本阅读、PDF/离线导出或老旧 Git 端等消费场景，再评估类型白名单（优先 flowchart TD / sequenceDiagram / stateDiagram-v2），直接修订本节即可。

## 四、废弃（Tombstone）

文档过时不删除、不改写正文：
1. frontmatter 改 `status: deprecated`（updated 同轮刷新）；
2. 标题下加墓碑行：`> [Deprecated YYYY-MM-DD] 已被 <X> 取代 → <继任文档相对路径>`；
3. 检索入口：`grep -rn 'status: deprecated' docs`。

单文件废弃不动 `docs/README.md` 索引条目——废弃时索引零操作即零漂移面；可见性由“条目仍在索引 + 打开首行即墓碑 + 检索入口”三层兑底。

域级变迁（拆分/合并/整体迁移）不做单文件墓碑，改用域墓碑：
1. 域内 active 文档按 §五 迁移至新域（索引同步 + 全仓引用修复）；
2. 原域目录只留一个 `README.md` 域墓碑：frontmatter 标 deprecated，标题下墓碑行指向新域；
3. 按 §〇 三处同步收尾：本节域表移除旧域、索引域头收敛为仅剩的域墓碑条目（定位句写明去向，如“已拆分为 X 域 / Y 域”）、`stock-calculator-service-index` 更新文档落点列。

docs/archive/ 暂不建立；未来批量清理时再启用。

## 五、引用与移动

- 文档间引用一律仓库相对路径：`docs/<域>/<文件>.md`。
- 移动/重命名文档必须：① 同步更新 docs/README.md 索引；② 全仓 grep 修复引用——不用 include_pattern（glob 锚定项目根全路径，写 `docs/**` 会假阴性漏检），用精确文件名模式逐项验证。

## 六、索引维护

docs/README.md 是纯结构索引（域 → 文档 → 一句话定位），不加状态列——状态以各文档头部 frontmatter 为唯一事实源，索引不重复（防双源漂移）。

## 七、反模式（禁止）

- docs/ 根目录平铺新文档
- 跳过或省略 frontmatter
- 改正文不刷新 updated
- ASCII 画图
- 过程记忆写入 docs/
- README 索引抄写 status/updated（双源漂移）

## 八、写后自检 lint

改完 docs 后在仓库根目录执行以下两条命令（单条保持短，已实测可执行；regex 锚点 `$/` 不触发 workflow 对 `$VAR`/`$(...)` 形式的拦截），输出为空即合规：

```sh
awk 'FNR==1&&!/^---/{print FILENAME " 缺frontmatter"} FNR==2&&!/^status: (draft|active|deprecated)$/{print FILENAME " status异常"} FNR==3&&!/^updated: [0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]$/{print FILENAME " updated异常"} FNR==4&&!/^---/{print FILENAME " 字段超量"}' docs/*/*.md docs/README.md
find docs -maxdepth 1 -name '*.md' ! -name README.md
```

第一条查 frontmatter 四项（缺开头/status 非法/updated 非法/字段超量），第二条查根目录平铺。

收集与覆盖校验跑仓库 `scripts/agent-tools/docs-index-lint.sh`（可推导信息不建第二份拷贝，现场 derive；已入 toolbox 项目池）：默认输出 docs 全量收集视图（路径 + status + updated，status= 为空即 frontmatter 异常）并双向校验 README↔docs 覆盖（README 链接必须存在；每个非 README 文档必须在 README 有条目，域墓碑 README 天然豁免）；`-c` 仅收集视图。退出码非 0 = 有问题。

另设 updated 漏刷软自查（改完当晚跑，只提醒不拦截）：

```sh
git --no-pager diff --name-only HEAD -- docs
```

列出未提交（含已暂存）动过的文档后，逐一核对 updated 是否按 §一 处理（实质修改刷新 / typo·引用修复豁免）。机器无法机械区分实质/基础设施改动，硬校验（hook/CI 强制 updated=当天）会与 §一 例外条款冲突，故只做软自查。
