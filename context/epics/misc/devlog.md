---
dev-loop: devlog
format: v1
epic: misc
total-merged: 3
last-merge: 2026-09-16
---

# misc 开发过程日志

（散修/小改动按行追加；≥5 条自动归并进 memory.md）

## 追加区
- [2026-09-16] [变更]: memo-collector 规约修订——/next 候选优先级与候选池/执行台原则（断点唯一执行台，todos 保持 [ ] 至完成流转）、done.md 300 行软阈值 /done 顺手归档、自动收集触发点收敛（子任务收尾/显式搁置/显式指令，严禁逐轮碎写）；agent-skill-system §3.2 失真表述同步
- [2026-09-16] [变更]: memo-collector v2.1 补丁——许愿池准入拦截+边缘条目确认、语义判重、脏读校验（人工 [x] 自动回收 done.md）、(block) 阻塞最高优先、/todo-groom 语义洗盘、分支结算与行级并集冲突口径（todos 留 git 不 gitignore）；agent-skill-system 2.1/3.2/4.1 同步
- [2026-09-16] [变更]: 隐性资产漏斗落地——memo-collector 七类型(新增测试)/未验证假设点名信号/咒语→SKILL.md 进化建议(经确认执行)、backend-dev §2.6 与 frontend-dev §4 边界推演防腐注释；agent-skill-system.md 补 §1.7 信息漏斗(隐性资产分流模型)并同步 2.1/2.2/3.2/3.3/4.1/4.5
- [2026-09-16] [变更]: agent-skill-system.md 新增 §3.4 agent-toolbox 设计原则与使用说明（分层图/七原则/命令面九命令/典型流程/现状注），原环境硬约束顺延为 §3.5，§3.3 场景速查补 agent-toolbox 行；§八 lint 与 docs-index-lint 均过
- [2026-09-16] [变更]: agent-toolbox v1 埋点——元工具 run-hooks 逐工具追加 usage-ledger.jsonl（ts/name/scope/exit/ms/src，append-only fail-open），list 派生 last_run 列；探针端到端验证 OK/FAIL/预检拒绝三路径通过，僵尸消费端待池子扩大后再做
- [2026-09-16] [变更]: toolbox v1.2 check 门禁 secret 形状扫描落地（7 类正则逐行、命中拒收不搬家、collect/check 双接线、自检金丝雀好坏样本；修复 \b 对下划线复合词 DB_PASSWORD 漏抓）；todos「secret 扫描」完成流转
- [2026-09-17] [变更]: copilot 记忆固化与画像抽取评估完成（MQ 触发/data worker LLM 归并/水位 CAS 幂等），设计文档落 docs/copilot/memory-profile.md（draft 待评审）并同步 README 索引，§八 lint 全绿
- [2026-09-17] [变更]: memory-profile.md 评审修正落地——对话片段成对下发（代词消解）、topic 枚举池+main 入库校验双保险、pinned 置顶混合召回（v2 留 pgvector 演进）、截断标记+prompt 容错声明；决策记录增至 11 条
- [2026-09-17] [变更]: memory-profile.md §七 重写——注入成本固定预算分段（画像/置顶/普通记忆/近3天历史，总封顶约6.1k字符）、近期历史排除当前会话、冷启动空注入懒积累；决策 #12
- [2026-09-17] [变更]: memory-profile.md 画像判定增强——分层口径入§一，显式提炼「选择+权衡+回复偏好」高价值信号，画像扩为四字段(+responsePreferences)，决策 #13
- [2026-09-17] [变更]: memory-profile.md 新增记录内容规范——六类记录类型表（选择/权衡/禁忌/回复偏好/习惯偏好/目标阶段）+「结论+权衡」条目约定，prompt 级口径不加列；§一分层口径修正为窗口逐段固化；决策 #14
