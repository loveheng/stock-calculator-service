#!/bin/sh
# 本地 JVM 启动器：按模块名（或任意 FQCN 类名）启动 Spring Boot 应用。
# .env 全量导出（已存在于环境中的变量优先，不被 .env 覆盖）；
# 容器内中间件主机名（postgres/redis/lavinmq）自动重写为 localhost（本机 JVM 场景）。
#
# toolbox-script
# format: v1
# name: run-jvm-module
# summary: 本地 JVM 启动/停止/状态/重启各 Java 模块（main/data/mcp/notify/orchestration 或任意类），自动加载 .env
# trigger: manual
# verbs: run,stop,status,restart
# cat: ops
# after: run-native-rest
# alias: jm
# platform: unix

usage() {
  cat <<'EOF'
用法: sh scripts/agent-tools/run-jvm-module.sh <模块> [-- 附加参数...]
       sh scripts/agent-tools/run-jvm-module.sh <stop|status|restart> [模块|all]
       sh scripts/agent-tools/run-jvm-module.sh --class <FQCN> --module <模块目录> [-- 附加参数...]
模块: main | data | mcp | notify | orchestration
  main           com.zzh.stock_calculator.StockCalculatorApplication
  data           com.zzh.stock_calculator.data.DataServiceApplication
  mcp            com.zzh.stock_calculator.mcp.StockMcpApplication
  notify         com.zzh.stock_calculator.notify.NotifyApplication
  orchestration  com.zzh.stock_calculator.orchestration.OrchestrationApplication
选项:
  --daemon  后台运行（-d 短写；日志仍定向 /tmp/logs/<服务名>.log，脱终端存活）
  --dry-run 干跑预览（-n 短写；stop/restart 只显示将做什么，不动真进程）
  --build   强制重新 compile 并重建 classpath（默认仅首次生成）
  --stop    停止指定模块的本机 JVM 进程（等价于子命令 stop，保留兼容）
  --json    快速预检（不启动应用）
  --help    本帮助
说明:
  - .env 逐行解析导出（不 source，防注入）；已导出的同名环境变量优先
  - 日志输出到 /tmp/logs/<服务名>.log（覆盖写，每次启动清空；--class 用法取类简单名）
  - POSTGRES_URL/POSTGRES_URL_MCP/RABBIT_MGMT_URL 中 //postgres:、//lavinmq: 重写为 localhost
  - REDIS_HOST/RABBIT_HOST 为容器主机名时重写为 localhost
  - java 探测顺序: JAVA_HOME -> /opt/GraalVM25 -> PATH
  - llm / contract 为库模块，不可启动
示例:
  sh scripts/agent-tools/run-jvm-module.sh main
  sh scripts/agent-tools/run-jvm-module.sh main --daemon
  sh scripts/agent-tools/run-jvm-module.sh data -- --worker.enable=false
  sh scripts/agent-tools/run-jvm-module.sh --class com.zzh.stock_calculator.mcp.StockMcpApplication --module mcp
  sh scripts/agent-tools/run-jvm-module.sh stop main
  sh scripts/agent-tools/run-jvm-module.sh stop all
  sh scripts/agent-tools/run-jvm-module.sh status
  sh scripts/agent-tools/run-jvm-module.sh restart main
  toolbox run jm main
  toolbox run jm -- mcp --daemon   （后台启动，stop/status 前后台通用）
  toolbox stop jm all        （元工具动词路由，等价 toolbox run jm stop all）
  toolbox status jm
EOF
}

# 模块名 -> (目录, 主类)
resolve_module() {
  case "$1" in
    main)          MOD_DIR=stock-calculator-main;          MAIN_CLASS=com.zzh.stock_calculator.StockCalculatorApplication ;;
    data)          MOD_DIR=stock-calculator-data;          MAIN_CLASS=com.zzh.stock_calculator.data.DataServiceApplication ;;
    mcp)           MOD_DIR=stock-calculator-mcp;           MAIN_CLASS=com.zzh.stock_calculator.mcp.StockMcpApplication ;;
    notify)        MOD_DIR=stock-calculator-mcp-notify;    MAIN_CLASS=com.zzh.stock_calculator.notify.NotifyApplication ;;
    orchestration) MOD_DIR=stock-calculator-orchestration; MAIN_CLASS=com.zzh.stock_calculator.orchestration.OrchestrationApplication ;;
    *)             MOD_DIR="";                             MAIN_CLASS="" ;;
  esac
}

# .env 逐行解析导出（环境变量优先；跳过注释/空行/无 = 行；兼容 CRLF）
load_env() {
  [ -f "$ROOT/.env" ] || { MSG="缺 .env：$ROOT/.env"; return 1; }
  while IFS= read -r line || [ -n "$line" ]; do
    line=$(printf '%s' "$line" | tr -d '\r')
    case "$line" in
      ''|\#*) continue ;;
      *=*) ;;
      *) continue ;;
    esac
    key=${line%%=*}
    val=${line#*=}
    case "$key" in
      *[!A-Za-z0-9_]*) continue ;;
    esac
    printenv "$key" >/dev/null 2>&1 && continue
    export "$key=$val"
  done < "$ROOT/.env"
  return 0
}

# 容器主机名 -> localhost（仅本机 JVM 直跑场景需要）
localize_hosts() {
  for v in POSTGRES_URL POSTGRES_URL_MCP RABBIT_MGMT_URL MCP_BROKER_URL; do
    eval "val=\${$v:-}"
    [ -n "$val" ] || continue
    nval=$(printf '%s' "$val" | sed -e 's#//postgres:#//localhost:#g' -e 's#//lavinmq:#//localhost:#g' -e 's#//redis:#//localhost:#g')
    [ "$nval" != "$val" ] && export "$v=$nval"
  done
  for v in REDIS_HOST RABBIT_HOST; do
    eval "val=\${$v:-}"
    case "$val" in postgres|redis|lavinmq) export "$v=localhost" ;; esac
  done
}

# 兼容已有脚本的默认值（run-jvm-watch.sh 同口径）
default_env() {
  : "${POSTGRES_USER:=root}"
  : "${POSTGRES_URL:=jdbc:postgresql://localhost:5432/scs}"
  export POSTGRES_USER POSTGRES_URL
}

find_java() {
  if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
    JAVA_BIN="$JAVA_HOME/bin/java"
  elif [ -x /opt/GraalVM25/bin/java ]; then
    JAVA_BIN=/opt/GraalVM25/bin/java
  else
    JAVA_BIN=java
  fi
}

# 停止一个运行中的 JVM 实例（按主类 FQCN 匹配 cmdline，先 TERM 后 KILL）
stop_one() {
  if pgrep -f "java.*$MAIN_CLASS" >/dev/null 2>&1; then
    if [ "$DRY_RUN" = "1" ]; then
      pid=$(pgrep -f "java.*$MAIN_CLASS" | head -1)
      echo "[dry-run] 将停止 $MAIN_CLASS pid=$pid（未执行）"
      return 0
    fi
    pkill -f "java.*$MAIN_CLASS"
    sleep 1
    pgrep -f "java.*$MAIN_CLASS" >/dev/null 2>&1 && pkill -9 -f "java.*$MAIN_CLASS"
    echo "[run-jvm-module] 已停止 $MAIN_CLASS"
  else
    echo "[run-jvm-module] 未在运行：$MAIN_CLASS"
  fi
}

stop_service() {
  [ -z "$MAIN_CLASS" ] && [ -n "$MODULE" ] && resolve_module "$MODULE"
  if [ "$MODULE" = "all" ]; then
    for m in main data mcp notify orchestration; do
      resolve_module "$m"
      stop_one
    done
    return 0
  fi
  if [ -z "$MAIN_CLASS" ]; then
    echo "错误：stop 需要模块名、all 或 --class <FQCN>" >&2
    return 2
  fi
  stop_one
}

status_service() {
  printf '%-14s %-8s %-7s %s\n' "MODULE" "STATE" "PID" "LOG"
  for m in main data mcp notify orchestration; do
    resolve_module "$m"
    pid=$(pgrep -f "java.*$MAIN_CLASS" | head -1)
    if [ -n "$pid" ]; then
      printf '%-14s %-8s %-7s %s\n' "$m" "running" "$pid" "/tmp/logs/$m.log"
    else
      printf '%-14s %-8s %-7s %s\n' "$m" "stopped" "-" "/tmp/logs/$m.log"
    fi
  done
}

# 已知失败模式 → remedy 提示（AI 拿到报错即拿到修复命令，省一轮探查）
diagnose_failure() {
  LOG="$1"
  grep -q "Client failed to initialize\|Did not observe any item" "$LOG" 2>/dev/null && {
    echo "[remedy] MCP client 启动期超时——main 依赖 orchestration(:18083)，orchestration 依赖 mcp(:18081)。" >&2
    echo "[remedy] 依序后台启动: toolbox run jm -- mcp --daemon && toolbox run jm -- orchestration --daemon && toolbox restart jm main" >&2
  }
  grep -q "Port 180\|was already in use\|BindException" "$LOG" 2>/dev/null && {
    echo "[remedy] 端口被占——先查归属: sh scripts/agent-tools/run-jvm-module.sh status; 冲突进程用 toolbox stop jm <模块> 停止。" >&2
  }
  grep -q "Connection refused\|FATAL: .*password" "$LOG" 2>/dev/null && {
    echo "[remedy] 中间件连接失败——确认容器健康: docker ps --filter name=postgres --filter name=lavinmq --filter name=redis; 口令在根目录 .env。" >&2
  }
  return 0
}

preflight() {
  ROOT=$(cd "$(dirname "$0")/../.." && pwd)
  REMEDY=""
  [ -x "$ROOT/mvnw" ] || { MSG="缺可执行的 ./mvnw"; REMEDY="在仓库根执行 git status 确认 mvnw 存在并可执行"; return 1; }
  [ -f "$ROOT/.env" ] || { MSG="缺 .env：$ROOT/.env（参考 .env.example 创建）"; REMEDY="cp .env.example .env 后填充真实口令"; return 1; }
  grep -qE '^POSTGRES_PASS=' "$ROOT/.env" || { MSG=".env 缺 POSTGRES_PASS"; REMEDY="参考 .env.example 补 POSTGRES_PASS=一行"; return 1; }
  [ -n "$MAIN_CLASS" ] || { MSG="模块未指定或不可识别（--help 看支持列表）"; REMEDY="可用模块: main data mcp notify orchestration（--help 看主类映射）"; return 1; }
  [ -d "$ROOT/$MOD_DIR" ] || { MSG="模块目录不存在：$MOD_DIR"; REMEDY="确认模块目录名，或用 --class + --module 指定"; return 1; }
  MSG="预检通过：.env 与 ./mvnw 就绪，模块=$MOD_DIR 类=$MAIN_CLASS"
}

main() {
  preflight || exit 1
  load_env || exit 1
  localize_hosts
  default_env
  find_java
  cd "$ROOT/$MOD_DIR" || exit 1
  CP_FILE=target/jvm-cp.txt
  if [ "$FORCE_BUILD" = "1" ] || [ ! -d target/classes ] || [ ! -f "$CP_FILE" ]; then
    echo "[run-jvm-module] 编译 $MOD_DIR 并生成 classpath ..."
    "$ROOT/mvnw" -q compile dependency:build-classpath -Dmdep.outputFile="$CP_FILE" || {
      echo "[run-jvm-module] 构建失败" >&2; exit 1; }
  fi
  CP="target/classes:$(cat "$CP_FILE")"
  # 日志：/tmp/logs/<服务名>.log，覆盖写（每次启动清空旧日志）
  LOG_NAME="$MODULE"
  [ -n "$LOG_NAME" ] || LOG_NAME=$(printf '%s' "$MAIN_CLASS" | sed 's/.*\.//')
  LOG_DIR=/tmp/logs
  LOG_FILE="$LOG_DIR/$LOG_NAME.log"
  mkdir -p "$LOG_DIR" 2>/dev/null || { echo "[run-jvm-module] 无法创建日志目录 $LOG_DIR" >&2; exit 1; }
  echo "[run-jvm-module] 启动 $MAIN_CLASS（$JAVA_BIN）"
  if [ "$DAEMON" = "1" ]; then
    echo "[run-jvm-module] 日志: $LOG_FILE（覆盖写，后台运行，tail -f 跟踪）"
    nohup "$JAVA_BIN" -cp "$CP" "$MAIN_CLASS" $EXTRA_ARGS > "$LOG_FILE" 2>&1 &
    APP_PID=$!
    sleep 1
    if kill -0 "$APP_PID" 2>/dev/null; then
      echo "[run-jvm-module] 已后台运行 pid=$APP_PID（stop: sh scripts/agent-tools/run-jvm-module.sh stop $LOG_NAME）"
      exit 0
    fi
    echo "[run-jvm-module] 后台进程即刻退出，查看日志: $LOG_FILE" >&2
    diagnose_failure "$LOG_FILE"
    tail -5 "$LOG_FILE" >&2
    exit 1
  fi
  echo "[run-jvm-module] 日志: $LOG_FILE（覆盖写，tail -f 跟踪，Ctrl-C 停止）"
  exec "$JAVA_BIN" -cp "$CP" "$MAIN_CLASS" $EXTRA_ARGS > "$LOG_FILE" 2>&1
}

MSG=""
MODULE=""
MOD_DIR=""
MAIN_CLASS=""
FORCE_BUILD=0
JSON=0
DAEMON=0
DRY_RUN=0
SUBCMD=""
EXTRA_ARGS=""
while [ $# -gt 0 ]; do
  case "$1" in
    --build) FORCE_BUILD=1 ;;
    --json) JSON=1 ;;
    --daemon|-d) DAEMON=1 ;;
    --dry-run|-n) DRY_RUN=1 ;;
    --stop) SUBCMD=stop ;;
    stop|status|restart) SUBCMD="$1" ;;
    --help|-h) usage; exit 0 ;;
    --class) MAIN_CLASS="$2"; shift ;;
    --module) MODULE="$2"; shift ;;
    --) shift; EXTRA_ARGS="$*"; break ;;
    -*) usage >&2; exit 2 ;;
    *) MODULE="$1" ;;
  esac
  shift
done

if [ -n "$MODULE" ] && [ -z "$MAIN_CLASS" ]; then
  resolve_module "$MODULE"
fi

if [ "$JSON" = "1" ]; then
  if preflight; then
    printf '{"status":"OK","severity":"info","message":"%s"}\n' "$MSG"
    exit 0
  fi
  printf '{"status":"FAIL","severity":"warn","message":"%s","remedy":"%s"}\n' "$MSG" "$REMEDY"
  exit 1
fi

case "$SUBCMD" in
  stop)
    stop_service
    exit $?
    ;;
  status)
    status_service
    exit 0
    ;;
  restart)
    if [ -z "$MAIN_CLASS" ] || [ "$MODULE" = "all" ]; then
      echo "错误：restart 需要单个模块名（all 无法前台逐个启动，请按 mcp → orchestration → main 依序手动启动）" >&2
      exit 2
    fi
    stop_one
    ;;
esac

if [ -z "$MAIN_CLASS" ] || [ -z "$MOD_DIR" ]; then
  # --class 裸用法：类名已知但未给模块目录时，必须补 --module
  if [ -n "$MAIN_CLASS" ] && [ -z "$MODULE" ]; then
    usage >&2; echo "错误：--class 需配合 --module 指定模块目录" >&2; exit 2
  fi
  usage >&2; exit 2
fi

main
