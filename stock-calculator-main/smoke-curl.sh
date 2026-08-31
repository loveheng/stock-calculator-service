#!/usr/bin/env bash
# main 模块 native 二进制 HTTP 冒烟: 验证 admin token 门禁 + ApiResponse 信封
cd "$(dirname "$0")"

./target/stock-calculator-service --server.port=19999 > /tmp/ni-curl.log 2>&1 &
PID=$!

# 等待启动（最多 15 秒）
for i in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15; do
  sleep 1
  if curl -s -o /dev/null http://localhost:19999/; then break; fi
done

echo "---- 无 token POST /api/admin/sync/history/stop ----"
curl -s -w '\nHTTP_CODE=%{http_code}\n' -X POST http://localhost:19999/api/admin/sync/history/stop

echo "---- 错误 token 同端点 ----"
curl -s -o /dev/null -w 'HTTP_CODE=%{http_code}\n' -X POST -H 'X-Admin-Token: wrong-token' http://localhost:19999/api/admin/sync/history/stop

kill -TERM "$PID" 2>/dev/null || true
wait "$PID" 2>/dev/null || true

echo "---- 应用日志末尾 5 行 ----"
tail -5 /tmp/ni-curl.log
