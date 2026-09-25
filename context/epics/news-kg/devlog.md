---
dev-loop: devlog
format: v1
epic: news-kg
total-merged: 1
last-merge: 2026-09-19
---

# news-kg 开发过程日志

（散修/小改动按行追加；≥5 条自动归并进 memory.md）

## 追加区
- [2026-09-20] [变更]: data 服务 native openai 反射崩溃修复——移植 main 生成器轮 17/18 为 stock-calculator-data/gen-openai-metadata.py（4,719 any-setter 类 + com.openai.core.** 200 类/1,074 方法），build-native.sh 增步骤 2.5 产物守卫；发现并绕过本地 AOT 产物陈旧陷阱（--no-pkg 二进制缺 kg worker，全量构建重生）；一次性 broker + mock LLM E2E 全绿（未建模字段响应零反射崩溃、PERMANENT 失败分类正常）
- [2026-09-25] [变更]: 修《新闻联播》KG 抽取长期不前滑——data 侧 openai-mini 档 max-tokens 8096 被 step-3.7-flash 的 thinking 吃光（finish_reason=length + content 空串 → TRANSIENT 重抽轮回），放大到 32768 后又卡 60s 读超时；配 OPENAI_MINI_MAX_TOKENS=32768 并把 data yml 的 timeout 改为 OPENAI_MINI_TIMEOUT 环境变量（现 240s），实证「kg extracted → kg done ingested」入库链路打通；docs 同步 §8.1 配额口径与 §6 对账条目
- [2026-09-25] [变更]: KgFuseService 加谓词受控归一——新增 kg.fuse.predicate-whitelist / predicate-fallback（词表显式落 application.yml，与 data 侧 SYSTEM_PROMPT 词表同源两份需双边同步），越界谓词（出席/显示/推动…）归一为「其他」，归一发生在 UNIQUE 判重之前以防塌陷后重复入库；词表误配为空时不归一（退化旧行为，避免静默全量打成一个值）；新增 KgFuseServiceTest 5 例（Mockito 免 MQ/DB）实测通过
- [2026-09-25] [变更]: openai-mini 档换模型到 SiliconFlow 的 Qwen/Qwen2.5-14B-Instruct（非 thinking），凭据沿用 OPENAI_MAX_API_KEY，配额同步回落 max-tokens 8192 / timeout 120s；同一份 prompt 横评显示 7B 在「别名覆盖 0/20、事件时间 0/11」两项断崖不合格，选型以这两项为硬指标；docs §8.1 改写为「8.1.1 thinking 踩坑 / 8.1.2 现行口径与横评表」
- [2026-09-25] [变更]: 受控谓词词表收敛到 contract 模块 KgControlledVocabulary（PREDICATES/PREDICATE_FALLBACK/PREDICATES_PROMPT）——data 侧 SYSTEM_PROMPT 改为 text block + formatted 注入词表，main 侧 KgProperties.Fuse 默认值取同一常量且 application.yml 不再显式覆盖词表（留空即走常量）；KgExtractWorkerTest 补「prompt 词表取自共享常量」守卫用例，并修其 setUp 缺失 tier base-url/api-key 导致 success 用例必失败（成功路径要落 model 名，LlmRegistry 三键 fail-fast）
- [2026-09-25] [验证]: 未执行: 历史变更本轮未重跑（当日变更自载验证：KG 抽取入库链路实证打通；KgFuseServiceTest 5 例通过；模型横评与谓词词表收敛实测通过）
