# AGENTS.md

本文件是给 AI 助手的开局指针（人类可忽略）。项目规范、任务断点、记忆均在 `context/` 体系与各 skill 内，此处只放一条铁律。

## 写脚本前先查工具池

遇到校验 / 巡检 / 起停 / 冒烟 / 部署类脚本需求，**先 `toolbox list --cat <类别>` 查现有工具直接用**，不要重新生成类似脚本；全量 `toolbox list` 仅在类别不明时用。需求 → 类目映射：构建/编译 → `build`；测试/验证/冒烟/回归 → `test`；部署/发布 → `deploy`；环境体检/依赖探测 → `env`；服务起停/进程运维 → `ops`；文档/索引校验 → `docs`。

- 调用：`toolbox run <工具名|别名> [args...]`（服务型工具可前置动词：`toolbox stop|status|restart <工具> ...`；`--chain` 可串 after 链）
- 预检：`toolbox run <工具> --json`（不执行本体）；危险操作先 `--dry-run`
- 开局/恢复：`toolbox suggest`（按 git 变更推荐工具）或 `toolbox recent`（近期使用摘要）
- 新建：确无合适工具时 `toolbox new <name>` → 实现 → `toolbox propose` 入提案队列等人 approve（项目专属放 `scripts/agent-tools/`）
- 规范：`toolbox spec`（SSOT）；机制说明：agent-toolbox skill
- 反向收敛：同一裸命令会话内重复 ≥3 次或跨会话再次手写 → 提炼为工具脚本提议入池（登记经确认，可批量），后续直接 `toolbox run` 复用

示例：起停 Java 模块用 `toolbox run jm main` / `toolbox status jm` / `toolbox stop jm all`，不要另写启动脚本。
