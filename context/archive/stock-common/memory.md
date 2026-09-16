---
dev-loop: memory
format: v1
epic: stock-common
total-merged: 1
last-merge: 2026-09-13
---

# stock-common：main 与 data 公共实体抽取评估（已完结归档）

> 定案：**不新建共享模块**——main 侧 8 处重复类是双路径门控（datasvc.mq.enabled / crawler.enabled）的回退保障，共享化破坏回退能力；两侧已适配性分叉、违反 contract 纯 POJO 定位（D9/R2），且漂移目前为零。
> 双窗口同步义务：TextCleaner 清洗规则、GroundingValidator 阈值、EmbeddingErrorClassifier 分类规则、ClsSignUtil 签名算法、MIN_TEXT_CHARS、EXTRACTOR_VERSION / PROMPT_VERSION 任一变更须 main↔data 两侧同步。
> 未竟事项：main 侧 8 处迁移过渡副本加注释标记（原断点，移交散修执行）；收尾判据 `datasvc.mq.enabled=true` 灰度稳定 → 删 main 侧副本并退役 crawler.enabled / announcement.process.enabled 开关。
> 【2026-09-15 散修核实】上行动作已失效：ed73cad（2026-09-12「代码精简」）已删除 main 侧全部迁移过渡副本（TextCleaner/GroundingValidator/EmbeddingErrorClassifier/ClsSignUtil/PdfTextExtractor 等）并退役三个门控开关（main Java 零匹配），删除强于标注、任务无对象；双窗口同步义务随之消解（MIN_TEXT_CHARS/EXTRACTOR_VERSION/PROMPT_VERSION 现仅存 data 侧）。main↔data 仅存 CfUsageFixingClient 同源副本——data 侧已带「同源复制」标记，属有意保留（contract 纯 POJO 权衡），非过渡品。
> 证据源：docs/architecture/data-service-split.md（§0 D2/D9、§3、§8、§9 R2）。

## 断点

- [断点] epic 已完结归档（2026-09-15）；唯一未竟动作已于 2026-09-15 核实为失效（见「未竟事项」下方核实注），恢复流程无需读本 epic。
