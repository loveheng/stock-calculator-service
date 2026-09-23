#!/bin/sh
# 全量回归（排除 TaskServiceTest：打真实 CLS API 必挂）
# 红线：不 source .env 整体注入——只取 POSTGRES_PASS 单键（本地库），
# CLOUDFLARE_* 不注入 → CfEmbeddingSmokeLiveTest 自动 skip，绝不真调 CF / 不碰远程库
#
# toolbox-script
# format: v1
# name: run-regression
# summary: 全量回归入口（本地库口令单键红线口径）
# trigger: manual
# params: POSTGRES_PASS,POSTGRES_URL,POSTGRES_USER
# alias: reg
# platform: unix

set -e

usage() {
  cat <<'EOF'
用法: sh scripts/agent-tools/run-regression.sh [--json|--help]
  裸跑    执行全量回归（长任务：需本地 PG 容器存活）
  --json  快速预检（不执行回归）：.env 口令与 ./mvnw 就绪性
  --help  本帮助
EOF
}

preflight() {
  ROOT=$(cd "$(dirname "$0")/../.." && pwd)
  [ -f "$ROOT/.env" ] || { MSG="缺 .env：$ROOT/.env"; return 1; }
  grep -qE '^POSTGRES_PASS=' "$ROOT/.env" || { MSG=".env 缺 POSTGRES_PASS"; return 1; }
  [ -x "$ROOT/mvnw" ] || { MSG="缺可执行的 ./mvnw"; return 1; }
  MSG="预检通过：.env 口令与 ./mvnw 就绪（未执行回归本体）"
}

main() {
  cd "$(dirname "$0")/../.."
  # env 优先（toolbox run 参数注入），.env 兜底（裸调通道）
  [ -n "${POSTGRES_PASS:-}" ] || PASS=$(grep -E '^POSTGRES_PASS=' .env | cut -d= -f2-)
  export POSTGRES_PASS="${POSTGRES_PASS:-$PASS}"
  : "${POSTGRES_URL:=jdbc:postgresql://localhost/scs}"
  : "${POSTGRES_USER:=root}"
  export POSTGRES_URL POSTGRES_USER
  ./mvnw install -Dtest='!TaskServiceTest' -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false
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
