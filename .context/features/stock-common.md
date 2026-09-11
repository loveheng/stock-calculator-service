# stock-common：main 与 data 模块公共实体抽取评估

> 结论：**不新建共享模块**。当前类重复是双路径门控下的过渡态，终态是删除 main 侧旧路径副本。
> 评估日期：2026-09-11 ｜ 依据：docs/data-service-split-design.md（§0 决策记录 / §3 迁移映射 / §8 回退策略）+ 两侧 diff 实证

## 1. 背景与问题

数据服务拆分（§8 阶段 4 进行中）后，main 与 stock-calculator-data 出现同名类，是否需要新建 stock-common 类共享模块收纳？

双路径门控现状（回退保障，§8）：

- `datasvc.mq.enabled`（环境变量 `DATASVC_MQ_ENABLED`，默认 **false**）：false → main 进程内跑完整旧管道；true → main 只发任务，data worker 执行
- `crawler.enabled`（main 当前 **true**）：main 自拉 CLS 电报
- 每阶段主服务保留原路径开关，任一阶段可切回进程内执行

## 2. 重复盘点（2026-09-11 实证）

| 类别 | 代表类 | 判定 |
|---|---|---|
| JPA 实体/仓库 | Announcement、ClsArticle、Stock 等 | 只在 main，无重复 ✅ |
| 消息协议 DTO | SliceSelection、StructureNode、AnnouncementProcessTask、EmbeddingComputeTask 等 | 已收口 contract ✅（main 旧 dto/SliceSelection.java、dto/StructureNode.java 已删，改用 contract 版本） |
| 处理管道类（8 处） | 见下表 | 两侧均为活代码 ⚠️ 过渡态 |

处理管道类清单与 main 侧活跃引用（删除前必查）：

| 重复类 | main 侧位置 | main 侧引用方 |
|---|---|---|
| CninfoClient + CninfoQueryResponse + CninfoTopSearchItem | announcement/client(/dto) | AnnouncementCollectService、AnnouncementProcessService |
| ExtractedDocument | announcement/dto | AnnouncementProcessService |
| parser 5 件套：PdfTextExtractor、TextCleaner、SlicingService、StructureTreeBuilder、TitlePatterns | announcement/parser | AnnouncementProcessService（进程内路径） |
| AnnouncementDistillService、GroundingValidator | announcement/service | AnnouncementProcessService |
| ClsSignUtil | crawler/util | TaskService、ClsDayTaskHelp（crawler.enabled=true 时活跃） |
| EmbeddingErrorClassifier | crawler/embedding/service | ArticleEmbeddingListener、ArticleEmbeddingService、EmbeddingBackfillTask |
| CfUsageFixingClient | crawler/embedding/config | EmbeddingConfig |

## 3. 结论：不抽共享模块（5 条理由）

1. **破坏回退能力**：main 副本是回退开关的一部分（§8），删不得；共享化让回退路径依赖 data 模块，两个部署形态耦合。
2. **两侧副本已适配性分叉**（diff 实证）：data 侧 TextCleaner 换精简版 AnnouncementParseProperties、AnnouncementDistillService 换 LlmGateway（main 用 LlmChainRouter）、全部加 @ConditionalOnProperty(datasvc.worker.enabled)（native AOT 构建期钉死）。共享化需先参数化分叉，为临时态做架构投资不值。
3. **历史决策反对**：2026-09-01 stock-calculator-common 已合并回 main，领域隔离改用包边界 + ModulithVerifyTest，新建共享模块走回头路。
4. **contract 纯 POJO 约束会被破坏**（D9/R2）：管道类依赖 Spring/RestClient/PDFBox，下沉违反 contract「禁引 Spring 类型」定位，并扩大两个 native image 的 AOT 配置面。
5. **漂移目前为零**：8 处副本均为语义平移——ClsSignUtil 算法逐行一致、EmbeddingErrorClassifier 分类规则一致、MIN_TEXT_CHARS=100 两侧同口径（data 侧注释标「与主服务 processOne 同口径」）。

## 4. 唯一真实成本：双窗口期同步修改

窗口期内改以下任一处，必须同步另一处（算法/阈值/正则类变更）：

- TextCleaner 清洗规则/正则、GroundingValidator 阈值
- EmbeddingErrorClassifier 分类规则、ClsSignUtil 签名算法
- MIN_TEXT_CHARS（AnnouncementProcessService / AnnouncementProcessWorker）
- 口径常量：PdfTextExtractor.EXTRACTOR_VERSION、AnnouncementDistillService.PROMPT_VERSION

## 5. 决定动作

- [ ] main 侧 8 处副本加注释标记：「迁移过渡副本，MQ 路径灰度稳定后随旧路径删除，修改须同步 data 侧」
- [ ] 收尾判据：`datasvc.mq.enabled=true` 灰度稳定 → 删 main 的 announcement/parser、announcement/client、AnnouncementDistillService、GroundingValidator、TaskService 拉取段、ClsSignUtil/ClsDayTaskHelp、embedding 进程内计算段 → `crawler.enabled` / `announcement.process.enabled` 开关退役（§3.2 留守清单不含这些类，终态即删除）
- 例外（默认不做）：若窗口期拉长且两侧算法都要演进，仅 ClsSignUtil / EmbeddingErrorClassifier 零依赖纯静态工具可考虑下沉 contract；警惕 contract 膨胀成 utils 大杂烩

## 6. 证据索引

- 设计决策：docs/data-service-split-design.md §0 D2/D9、§3.1/§3.2 迁移映射、§8 回退策略、§9 R2
- 配置门控：stock-calculator-main/src/main/resources/application.yml（datasvc.mq.enabled 默认 false、crawler.enabled true、announcement.process.enabled false）
- 平移实证：data 侧 ClsSignUtil javadoc「算法不变」；AnnouncementProcessWorker 注释「与主服务 processOne 同口径」

---

## 变更日志

（后续业务代码/配置变更在此按行追加；本文件创建轮次按豁免规则不记日志）
