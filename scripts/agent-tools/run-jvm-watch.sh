#!/bin/sh
# RabbitManagementClient vhost 双重编码修复验证（测试计划 P3-8 前置 / D3 缺陷回归）：
# JVM 起 main 应用约 80 秒（覆盖一次 cron 整分巡检 tick），抓 pipeline watch 日志。
# 预期：无 "Vhost %2F does not exist" 404，且日志出现队列深度统计。
#
# toolbox-script
# format: v1
# name: run-jvm-watch
# summary: monitor 巡检 JVM 侧验证（vhost 双重编码缺陷回归观察）
# trigger: manual
# params: POSTGRES_PASS,POSTGRES_URL,POSTGRES_USER
# alias: jw
# platform: unix

usage() {
  cat <<'EOF'
用法: sh scripts/agent-tools/run-jvm-watch.sh [--json|--help]
  裸跑    JVM 起 main 应用约 80 秒抓 pipeline watch 日志（长任务）
  --json  快速预检（不执行观察）：.env 与 ./mvnw 就绪性
  --help  本帮助
EOF
}

preflight() {
  ROOT=$(cd "$(dirname "$0")/../.." && pwd)
  [ -f "$ROOT/.env" ] || { MSG="缺 .env：$ROOT/.env"; return 1; }
  grep -qE '^POSTGRES_PASS=' "$ROOT/.env" || { MSG=".env 缺 POSTGRES_PASS"; return 1; }
  [ -x "$ROOT/mvnw" ] || { MSG="缺可执行的 ./mvnw"; return 1; }
  MSG="预检通过：.env 口令与 ./mvnw 就绪（未执行观察本体）"
}

main() {
  cd "$(dirname "$0")/../../stock-calculator-main"
  # env 优先（toolbox run 参数注入），.env 兜底（裸调通道）
  [ -n "${POSTGRES_PASS:-}" ] || PASS=$(grep -E '^POSTGRES_PASS=' ../.env | cut -d= -f2-)
  export POSTGRES_PASS="${POSTGRES_PASS:-$PASS}"
  : "${POSTGRES_URL:=jdbc:postgresql://localhost/scs}"
  : "${POSTGRES_USER:=root}"
  export POSTGRES_URL POSTGRES_USER
  ../../mvnw -q dependency:build-classpath -Dmdep.outputFile=target/jvm-cp.txt >/dev/null 2>&1
  CP="target/classes:$(cat target/jvm-cp.txt)"
  java -cp "$CP" com.zzh.stock_calculator.StockCalculatorApplication > /tmp/jvm-watch.log 2>&1 &
  APP_PID=$!
  sleep 80
  kill -9 "$APP_PID" 2>/dev/null || true
  pkill -9 -f StockCalculatorApplication 2>/dev/null || true
  echo "---- pipeline watch 日志 ----"
  grep -E "pipeline watch|pipeline alert" /tmp/jvm-watch.log | head -8
  echo "---- 404 复查 ----"
  grep -c "Vhost %2F does not exist" /tmp/jvm-watch.log || echo "0 (已修复)"
}

MSG=""
case "${1-}" in
  --help|-h)
    usage
    ;;
  --json)
    if preflight; then
      printf '{"status":"OK","severity":"info","message":"%s"}\n' "$MSG"
      exit 0
    fi
    printf '{"status":"FAIL","severity":"warn","message":"%s"}\n' "$MSG"
    exit 1
    ;;
  "")
    main
    ;;
  *)
    usage
    exit 2
    ;;
esac
