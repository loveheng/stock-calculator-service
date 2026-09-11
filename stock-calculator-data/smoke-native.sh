#!/usr/bin/env bash
# ============================================================================
# Data Service native 二进制独立冒烟（不重新编译，可重复运行）
#   1. 启动二进制（角色已在构建期钉死；运行期值经 SPRING_APPLICATION_JSON 注入
#      dummy 凭据——worker 的 CF/LLM fail-fast 在运行期实例化时同样触发）
#   2. 断言 Started/Tomcat 启动行 + ingest 端点存活（secret 空约定 503，缺签名 400）
# ============================================================================
set -e
cd "$(dirname "$0")"

BIN=./target/stock-calculator-data-service
[ -x "$BIN" ] || { echo "❌ 二进制不存在，先运行 build-native.sh"; exit 1; }

export SPRING_APPLICATION_JSON='{
  "datasvc": {
    "worker": {"enabled": true,
               "embedding": {"account-id": "smoke-dummy", "api-token": "smoke-dummy"}},
    "llm": {"base-url": "http://smoke.invalid", "api-key": "smoke-dummy", "model": "smoke-dummy"}
  }
}'

./target/stock-calculator-data-service --server.port=19999 > /tmp/ni-data-run.log 2>&1 &
BIN_PID=$!
SMOKE_OK=0
for i in 1 2 3 4 5 6 7 8; do
  sleep 1
  grep -qE 'Tomcat started|Started DataServiceApplication' /tmp/ni-data-run.log && SMOKE_OK=1 && break
done
HTTP_CODE=$(curl -s -o /dev/null -w '%{http_code}' -X POST http://localhost:19999/api/ingest/generic \
  -H 'Content-Type: application/json' --data '{}' --max-time 5 || true)
kill "$BIN_PID" 2>/dev/null || true
pkill -9 -f 'target/stock-calculator-data-service --server.port=19999' 2>/dev/null || true

if [ "$SMOKE_OK" = "1" ]; then
  echo "✅ 启动测试通过（完整日志: /tmp/ni-data-run.log）"
else
  echo "❌ 启动测试失败（日志末尾 25 行）："
  tail -25 /tmp/ni-data-run.log || true
  exit 1
fi

if [ "$HTTP_CODE" = "503" ] || [ "$HTTP_CODE" = "400" ]; then
  echo "✅ ingest 端点存活（HTTP $HTTP_CODE）"
else
  echo "❌ ingest 端点未预期响应：HTTP $HTTP_CODE（预期 503/400）"
  exit 1
fi
