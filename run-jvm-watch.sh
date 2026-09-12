#!/bin/sh
# RabbitManagementClient vhost 双重编码修复验证（测试计划 P3-8 前置 / D3 缺陷回归）：
# JVM 起 main 应用约 80 秒（覆盖一次 cron 整分巡检 tick），抓 pipeline watch 日志。
# 预期：无 "Vhost %2F does not exist" 404，且日志出现队列深度统计。
# 用法: sh run-jvm-watch.sh
cd "$(dirname "$0")/stock-calculator-main"
PASS=$(grep -E '^POSTGRES_PASSWORD=' ../.env | cut -d= -f2-)
export POSTGRES_PASS="$PASS"
export POSTGRES_URL="jdbc:postgresql://localhost/scs"
export POSTGRES_USER="root"
../mvnw -q dependency:build-classpath -Dmdep.outputFile=target/jvm-cp.txt >/dev/null 2>&1
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
