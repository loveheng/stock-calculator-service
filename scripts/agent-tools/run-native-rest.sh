#!/bin/sh
# main native REST 门禁冒烟（测试计划 P4-2）：带库口令复用 smoke-curl.sh
# 验证 admin token 门禁 + ApiResponse 信封
#
# toolbox-script
# format: v1
# name: run-native-rest
# summary: main native REST 门禁冒烟（admin token 门禁 + ApiResponse 信封）
# trigger: manual
# platform: unix

set -e

usage() {
  cat <<'EOF'
用法: sh scripts/agent-tools/run-native-rest.sh [--json|--help]
  裸跑    执行 REST 门禁冒烟（启动二进制 + curl 断言）
  --json  快速预检（不执行冒烟）：二进制/smoke-curl.sh/库口令就绪性
  --help  本帮助
EOF
}

preflight() {
  ROOT=$(cd "$(dirname "$0")/../.." && pwd)
  MAIN="$ROOT/stock-calculator-main"
  [ -f "$ROOT/.env" ] || { MSG="缺 .env：$ROOT/.env"; return 1; }
  grep -qE '^POSTGRES_PASS=' "$ROOT/.env" || { MSG=".env 缺 POSTGRES_PASS"; return 1; }
  [ -x "$MAIN/target/stock-calculator-service" ] || { MSG="native 二进制不存在：$MAIN/target/stock-calculator-service（先跑 build-native.sh）"; return 1; }
  [ -f "$MAIN/smoke-curl.sh" ] || { MSG="缺 $MAIN/smoke-curl.sh"; return 1; }
  MSG="预检通过：二进制/smoke-curl.sh/库口令就绪（未执行冒烟本体）"
}

main() {
  cd "$(dirname "$0")/../../stock-calculator-main"
  PASS=$(grep -E '^POSTGRES_PASS=' ../.env | cut -d= -f2-)
  export POSTGRES_PASS="$PASS"
  export POSTGRES_URL="jdbc:postgresql://localhost/scs"
  export POSTGRES_USER="root"
  sh smoke-curl.sh
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
