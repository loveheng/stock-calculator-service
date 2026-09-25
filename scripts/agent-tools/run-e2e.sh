#!/bin/sh
# E2E 分层执行（测试计划 P2，docs/architecture/module-split-test-plan.md）：
# 与 run-regression.sh 同一环境口径（只取 POSTGRES_PASS 单键注入，
# 不整体 source .env，防 CLOUDFLARE_* 等泄漏进测试环境）
# 前置：本地 broker（LavinMQ 容器）存活且队列为空（DELETE /api/queues 清空）
#
# toolbox-script
# format: v1
# name: run-e2e
# summary: 分模块 E2E 执行（RABBIT_E2E 门控，main|data 二选一）
# trigger: manual
# params: POSTGRES_PASS,POSTGRES_URL,POSTGRES_USER,RABBIT_E2E
# cat: test
# alias: e2e
# platform: unix

set -e

usage() {
  cat <<'EOF'
用法: sh scripts/agent-tools/run-e2e.sh main|data
      sh scripts/agent-tools/run-e2e.sh --json    快速预检（不执行 E2E）
      sh scripts/agent-tools/run-e2e.sh --help
前置：本地 broker（LavinMQ 容器）存活且队列为空
EOF
}

preflight() {
  ROOT=$(cd "$(dirname "$0")/../.." && pwd)
  [ -f "$ROOT/.env" ] || { MSG="缺 .env：$ROOT/.env"; return 1; }
  grep -qE '^POSTGRES_PASS=' "$ROOT/.env" || { MSG=".env 缺 POSTGRES_PASS"; return 1; }
  [ -x "$ROOT/mvnw" ] || { MSG="缺可执行的 ./mvnw"; return 1; }
  MSG="预检通过：.env 口令与 ./mvnw 就绪（未执行 E2E 本体，broker 就绪请自查）"
}

main() {
  case "${1-}" in
    main|data) MODULE="$1" ;;
    *) echo "用法: sh scripts/agent-tools/run-e2e.sh main|data"; exit 2 ;;
  esac
  cd "$(dirname "$0")/../.."
  # env 优先（toolbox run 参数注入），.env 兜底（裸调通道）
  [ -n "${POSTGRES_PASS:-}" ] || PASS=$(grep -E '^POSTGRES_PASS=' .env | cut -d= -f2-)
  export POSTGRES_PASS="${POSTGRES_PASS:-$PASS}"
  : "${POSTGRES_URL:=jdbc:postgresql://localhost/scs}"
  : "${POSTGRES_USER:=root}"
  export POSTGRES_URL POSTGRES_USER
  export RABBIT_E2E=true
  ./mvnw test -pl "stock-calculator-$MODULE" -Dtest='*E2ETest' -Dsurefire.failIfNoSpecifiedTests=false
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
    usage
    exit 2
    ;;
  *)
    main "$@"
    ;;
esac
