#!/bin/sh
# main native 二进制冒烟（测试计划 P4-2）：
# build-native.sh 内置 8s 冒烟不带 POSTGRES_PASS，连库失败报
# "Unable to determine Dialect" 属已知假象（native-build skill §三案底），
# 本脚本带真实库口令重跑启动冒烟并给出明确退出码。
#
# toolbox-script
# format: v1
# name: run-native-smoke
# summary: main native 二进制带库启动冒烟（明确退出码）
# trigger: manual
# params: POSTGRES_PASS,POSTGRES_URL,POSTGRES_USER
# alias: ns
# platform: unix

set -e

usage() {
  cat <<'EOF'
用法: sh scripts/agent-tools/run-native-smoke.sh [--json|--help]
  裸跑    启动 native 二进制冒烟（约 20s，需本地 PG 容器存活）
  --json  快速预检（不执行冒烟）：二进制与库口令就绪性
  --help  本帮助
EOF
}

preflight() {
  ROOT=$(cd "$(dirname "$0")/../.." && pwd)
  MAIN="$ROOT/stock-calculator-main"
  [ -f "$ROOT/.env" ] || { MSG="缺 .env：$ROOT/.env"; return 1; }
  grep -qE '^POSTGRES_PASS=' "$ROOT/.env" || { MSG=".env 缺 POSTGRES_PASS"; return 1; }
  [ -x "$MAIN/target/stock-calculator-service" ] || { MSG="native 二进制不存在：$MAIN/target/stock-calculator-service（先跑 build-native.sh）"; return 1; }
  MSG="预检通过：native 二进制与库口令就绪（未执行冒烟本体）"
}

main() {
  cd "$(dirname "$0")/../../stock-calculator-main"
  # env 优先（toolbox run 参数注入），.env 兜底（裸调通道）
  [ -n "${POSTGRES_PASS:-}" ] || PASS=$(grep -E '^POSTGRES_PASS=' ../.env | cut -d= -f2-)
  export POSTGRES_PASS="${POSTGRES_PASS:-$PASS}"
  : "${POSTGRES_URL:=jdbc:postgresql://localhost/scs}"
  : "${POSTGRES_USER:=root}"
  export POSTGRES_URL POSTGRES_USER
  timeout --kill-after=3 20 ./target/stock-calculator-service --server.port=19999 > /tmp/ni-run2.log 2>&1 || true
  pkill -9 -f 'target/stock-calculator-service --server.port=19999' 2>/dev/null || true
  grep -E 'Tomcat started|Started StockCalculator' /tmp/ni-run2.log
  echo "SMOKE_OK: native main started and bound port 19999"
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
