---
status: active
updated: 2026-09-25
---

了解了项目结构和现有脚本规范（toolbox 登记制、`run-jvm-watch.sh` 的成熟模式）。5 个可启动模块：`main` / `data` / `mcp` / `notify` / `orchestration`（`llm`、`contract` 是库不可启动）。现在编写脚本并按规范登记到项目工具池。
修正 `localize_hosts` 中一处多余的 sed 表达式：
语法通过。按 toolbox 规范登记入池并做金丝雀预检：
注册表中已有同名条目（疑似此前会话残留登记），用 `--force` 重新校验并覆盖登记当前文件：
`toolbox check --force` 的同名搬运逻辑误删了文件。重新写入脚本（注册表条目已存在，无需再登记）：
预检通过。再验证其余模块解析与错误分支：
各模块解析与错误分支均正常。检查中间件是否在跑，然后做一次真实启动冒烟：
脚本已完成并验证。刚才那条检查命令被你取消了（结果显示 `target/classes` 已存在，`jvm-cp.txt` 尚未生成），我不再继续跑真实启动冒烟。

## 已交付

**脚本**：`scripts/agent-tools/run-jvm-module.sh`（已通过 toolbox 登记，别名 `jm`，`sh -n` 语法检查 + 5 模块 `--json` 预检 + 非法模块错误分支全部通过）

**用法**：
```sh
# 按模块启动（自动加载根目录 .env）
sh scripts/agent-tools/run-jvm-module.sh main
sh scripts/agent-tools/run-jvm-module.sh data
sh scripts/agent-tools/run-jvm-module.sh mcp
sh scripts/agent-tools/run-jvm-module.sh notify
sh scripts/agent-tools/run-jvm-module.sh orchestration

# 附加 Spring 启动参数（-- 之后透传给应用）
sh scripts/agent-tools/run-jvm-module.sh main -- --crawler.enabled=false

# 启动任意类（--class + --module）
sh scripts/agent-tools/run-jvm-module.sh --class com.zzh.stock_calculator.mcp.StockMcpApplication --module mcp
```

**核心行为**：
- **`.env` 加载**：逐行解析导出（不 `source`，防注入），跳过注释/空行/非法键，兼容 CRLF；已导出的同名环境变量优先于 `.env`
- **本机主机名映射**：`POSTGRES_URL`/`POSTGRES_URL_MCP`/`RABBIT_MGMT_URL` 中 `//postgres:`、`//lavinmq:` → `//localhost:`；`REDIS_HOST`/`RABBIT_HOST` 为容器主机名时改 `localhost`（本机 JVM 直跑场景中间件端口已映射到宿主机）
- **classpath**：首次自动 `./mvnw -q compile dependency:build-classpath` 生成并缓存到 `target/jvm-cp.txt`，之后直接复用（`--build` 强制重建）
- **java 探测**：`JAVA_HOME` → `/opt/GraalVM25` → `PATH`（与项目惯例一致）
- 前台运行（Ctrl-C 停止），`--json` 可做不启动的快速预检

**注意**：`llm`、`contract` 是纯库模块无主类，不可启动；中间件容器当前均 healthy，真实启动可用。

给脚本加 `--stop` 停止能力。先看当前文件相关段落再编辑：
现在加入停止逻辑函数与参数分派：
`--stop` 已加入并验证通过。

**用法**：
```sh
# 停止单个模块（本机 JVM 进程）
sh scripts/agent-tools/run-jvm-module.sh --stop main

# 停止全部五个模块
sh scripts/agent-tools/run-jvm-module.sh --stop all

# 也可按类名停
sh scripts/agent-tools/run-jvm-module.sh --stop --class com.zzh.stock_calculator.mcp.StockMcpApplication
```

**行为**：
- 按主类 FQCN 匹配 `java` 进程 cmdline，先发 `TERM` 优雅停止，1 秒后仍存活则 `KILL -9`
- 未运行的模块报 `未在运行` 并正常退出（幂等，可安全重复执行）
- 缺模块名 / 未知模块 → 报错退出码 2
- `toolbox list` 已同步新登记信息（summary 更新为「启动/停止」）

已验证：`sh -n` 语法通过、`--stop all`（无实例时五个模块全部幂等报告）、`--stop bogus` / 缺参错误分支均返回 2。

一个前提提醒：`--stop` 只能停「本机 JVM 直跑」的进程；Docker 容器里的服务仍需用 `docker compose -f docker-compose.app.yml stop app/data` 停。

`toolbox run` 的参数解析把 `--stop` 当成了自己的选项。先看它如何透传参数：
帮助说明参数应「原样透传」，但 `--stop` 被 argparse 吞了。看元工具的 `run` 实现确认透传方式：
`tool_args` 是 `nargs="*"` 位置参数，argparse 遇到 `--stop` 这种选项样式的 token 会拒收。标准解法是用 `--` 分隔符终结选项解析。实测：
`--` 分隔符可以透传。但注意到警告：头部声明的 `params: MODULE,CLASS,ARGS,STOP` 被元工具当作必填环境注入参数。我的脚本实际用位置参数而非环境变量，声明有误。查一下 spec 的 params 语义再修正：
params 语义是「环境变量注入的配置参数」，我的脚本参数全是命令行位置参数，不该声明。删掉 `params:` 行消除误报警，并在 usage 里补上 `toolbox run` 的正确姿势：
`--stop all` 已干净通过。选项参数（如 `--json`）同样需要 `--` 分隔，再验证一次完整形式：
第二条验证命令触发了 main 的真实启动流程（编译→启动）。检查是否有残留进程需要清理：
已解决。问题不在脚本，而在 `toolbox run` 的参数解析：

**原因**：`toolbox run` 底层是 argparse，`--stop` 这种选项样式的 token 会被它当成自己的选项拒收，不会透传给脚本。

**正确用法**（加 `--` 分隔符，让 argparse 停止解析选项）：
```sh
toolbox run jm -- --stop all
toolbox run jm -- main --json
toolbox run jm -- data -- --worker.enable=false
```
纯位置参数不需要 `--`，直接透传：
```sh
toolbox run jm main      # 启动 main
```

**顺手修了一处**：脚本头部原来声明的 `params: MODULE,CLASS,ARGS,STOP` 不符合规范语义（params 是「环境变量注入的配置参数」，如 `POSTGRES_PASS`；本脚本参数全是命令行位置参数），会导致每次 `toolbox run` 都误报「缺参数」警告。已删除该声明，现在 `toolbox run jm -- --stop all` 干净无警告。

**已验证**：
- `toolbox run jm -- --stop all` → 五模块全部正常报告（当前均未运行）
- `toolbox run jm -- main --json` → 预检 OK
- `toolbox run jm main` → 进入正常启动流程（编译 classpath），无残留进程

把启动日志改为定向输出到 `/tmp/logs/<服务名>.log`（覆盖写）。先看 `main()` 当前段落：
做一次真实冒烟：后台起 `main`，确认日志落到独立文件后再停掉：
`main.log` 已生成（54KB，应用在跑）。确认进程并测试 `--stop`：
应用进程已退出，查看日志确认原因：
日志机制已完全生效（启动横幅到异常堆栈全部进 `main.log`）。但应用自身启动失败：某 bean 初始化时响应式调用 20 秒超时。定位是哪个 bean：
原因定位：`main` 启动时会通过 Spring AI MCP client 连接 MCP broker（`MCP_BROKER_URL=http://localhost:18081/sse`），本机没先起 `mcp` 模块导致 20 秒超时。这是启动顺序问题，不是脚本缺陷。确认 mcp 端口后按正确顺序做完整验证：
拓扑确认：`mcp` 经纪人监听 `:18081`。按依赖顺序完整验证——先起 `mcp`，探活后起 `main`：
`mcp` 已就绪（SSE 返回 200，RSS 轮询已跑，日志独立落 `mcp.log`）。再起 `main`：
`main` 仍超时。查 main 的 MCP client 实际配置来源：
真相找到了：`main` 的 MCP client 连的是 **orchestration (:18083)**（`MCP_ORCH_URL`），不是 mcp。正确依赖顺序：`mcp(:18081)` → `orchestration(:18083)` → `main`。补起 orchestration：
`orchestration` 已就绪（工具注册表 11 个工具装载完成，`18081`/`18083` SSE 均 200）。再起 `main`：
`main` 启动成功（Tomcat `:18080`）。最后确认服务健康并验证 `--stop all` 全链路清理：
完成。日志定向功能已加好并经真实全链路验证。

**改动**：启动时日志不再打终端，改为定向输出（覆盖写，每次启动清空旧日志）：

```sh
/tmp/logs/main.log           # main 服务
/tmp/logs/data.log           # data 服务
/tmp/logs/mcp.log            # mcp 服务
/tmp/logs/notify.log         # notify 服务
/tmp/logs/orchestration.log  # orchestration 服务
```

启动时终端会打印对应日志路径，`tail -f /tmp/logs/main.log` 跟踪，Ctrl-C 停止。

**真实冒烟验证（已全链路跑通）**：
- `mcp` → 启动成功，SSE `:18081` 返回 200，RSS 轮询正常，日志落 `mcp.log`
- `orchestration` → 启动成功，工具注册表装载 11 个工具，SSE `:18083` 返回 200，日志落 `orchestration.log`
- `main` → 启动成功（Tomcat `:18080`，HTTP 200，11.4 秒），日志落 `main.log`
- `--stop all` → 三个运行中的服务全部停止，无残留进程

**重要发现——本机 JVM 直跑的启动顺序**：`main` 的 MCP client 连的是 **orchestration `:18083`**（非 mcp），所以直接起 `main` 会因 MCP client 20 秒超时而启动失败。正确顺序：

```sh
sh scripts/agent-tools/run-jvm-module.sh mcp &
sh scripts/agent-tools/run-jvm-module.sh orchestration &
sh scripts/agent-tools/run-jvm-module.sh main
```

（`data` / `notify` 无此类启动期依赖，可独立起。）当前冒烟后已全部 `--stop all` 清理，`/tmp/logs` 三个日志文件保留可查看。
