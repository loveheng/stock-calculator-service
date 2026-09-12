#!/bin/sh
# E2E 分层执行（测试计划 P2，docs/module-split-test-plan.md）：
# 与 run-regression.sh 同一环境口径（只取 POSTGRES_PASSWORD 转 POSTGRES_PASS，
# 不整体 source .env，防 CLOUDFLARE_* 等泄漏进测试环境）
# 前置：本地 broker（LavinMQ 容器）存活且队列为空（DELETE /api/queues/%2F/{name}/contents）
# 用法: sh run-e2e.sh main | data
set -e
cd "$(dirname "$0")"
MODULE="$1"
PASS=$(grep -E '^POSTGRES_PASSWORD=' .env | cut -d= -f2-)
export POSTGRES_PASS="$PASS"
export POSTGRES_URL="jdbc:postgresql://localhost/scs"
export POSTGRES_USER="root"
export RABBIT_E2E=true
./mvnw test -pl "stock-calculator-$MODULE" -Dtest='*E2ETest' -Dsurefire.failIfNoSpecifiedTests=false
