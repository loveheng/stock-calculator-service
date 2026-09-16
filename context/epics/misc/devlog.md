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
