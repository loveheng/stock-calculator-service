---
dev-loop: memory
format: v1
epic: misc
total-merged: 3
last-merge: 2026-09-16
---

# misc：散修与小改动挂靠（常驻杂项 epic）

（协议见 dev-loop skill §3「杂项挂靠」：散修/小改动统一挂靠本 epic；长成大功能则 /bind 转正，届时此处归并只留一行索引，日志不搬家）

## CI/构建

- docker-image.yml = changes 检测（dorny/paths-filter）+ build-main/build-data 双 job；contract/根POM/mvnw 变更双端重建；data 模块 Dockerfile.native（ghcr.io/<repo>-data，8080/ingest）；main build-native.sh 需父POM+contract install（CI 冷缓存必挂）。（2026-09-12）
- v2.5 worker 变体退役：删 Dockerfile.worker、build-data-worker job、build-native.sh VARIANT 分支（二进制恒三角色全开）、compose data-worker 块。（2026-09-13）

## data 拓扑/设计（pull-loop + 日历任务）

- 日历型定时任务定案：拒绝 cron→per-message TTL 种子衰变（复活已否方案、违反设计不变量 3、长 TTL 种子不可撤销）；终态 = main 看门狗 CAS 认领 + work 队列一次性直发（data 侧无续种无 delay）；pull_task_config 增 schedule_mode/cron_expression/timezone/next_expected_time 四列，LOOP 行不落 next_expected_time（健康口径 = last_renew_time 新鲜度，所有者确认）。（2026-09-13）
- 首个日历任务 task.hello.world 落地（每日 07:00 Asia/Shanghai）；watchdog 拆双节奏（watch 30min + calendarClaim 60s CAS）；main 383 / data 79 用例绿。（2026-09-13）

## 转正索引

- data 单镜像多副本改造（v2.5，2026-09-13）已转正为 epic: data-single-image——决策/踩坑/验收随迁该 epic memory.md。

## skill 与 docs 体系建设

- docs/ 整合为 8 功能域子目录（域内去前缀重命名）+ docs/README.md 总索引 + 24 篇补 frontmatter（全 active）；规范沉淀为 skill stock-calculator-docs（frontmatter/域落点/墓碑/§八写后 lint）。（2026-09-15）
- 后端编码规范 skill 定名 stock-calculator-backend-dev（原 cls-article-patterns 重构：事实指针化→workflow/service-index、3.3/3.4.3 去重、域枚举模式化、description 对偶化），全仓引用同轮同步为惯例。（2026-09-15）
- 体系 skill 强化：dev-loop audit 十项清单（新增第 10 项事实指针化抽查）、dev-guide §5 检测网同步、workflow 补「本地依赖服务」（compose 三件套/scs-net/.env 口令口径）、新增 .agents/prompts/task-template.md 任务派发模板。（2026-09-15）
- skill 体系手册落 docs/architecture/agent-skill-system.md：设计意图（六原则+分层图+裁决链）、skill 清单与触发、会话生命周期图、指令手册、场景速查、治理规则与反模式；v2 补 §1.5 冷启动（global 包无远端：拷贝兜底/推荐配远端 clone）、§1.6 IDE 兼容（非 Zed 手动读 SKILL.md 兜底）、§4.3 灾难恢复与记忆防腐；memo-collector 补录后共 12 skill、文档 8 处落点同步。（2026-09-15/16）
- skill 治理：dev-guide 项目数据解耦达成「换项目零编辑」（项目命令路由至 service-index 新增「命令速查」节，前端项目索引同补）；dev-loop audit 第 10 项扩为 skill 卫生抽查（事实指针化 + description 预算 ≤~250 目标/550 硬顶）；环境事实归一至 workflow「环境与工具链」（native-build/runtime-metadata 改单行指针）；dev-loop 增护栏总纲「求助人类优先」、§4 归并事故回滚纪律（严禁 AI 自修，git checkout 回滚）、§9 /help 指令。（2026-09-15）
- stock-common 归档核实：其未竟事项已失效——main 侧 8 处迁移过渡副本与门控开关（datasvc.mq.enabled 等）已由 ed73cad 代码精简删除，任务无对象，archive memory 已补核实注；native-build §一 模块清单过期点已点名上报（未擅动）。（2026-09-15）
- 新增全局 skill memo-collector：AI 回复/用户口述中的待办/风险/完成自动收集去重落盘——context/todos.md 按域分节（活跃 epic + misc 兜底，与 devlog 挂靠同规则）+ context/done.md 完成流转（记来源域）；指令 /todo /todos /tdone /todo-clean；与 dev-loop 互补（断点=唯一下一步，本表=全部积压）。（2026-09-16）

## 断点

- [断点] 下一步：等待散修任务
