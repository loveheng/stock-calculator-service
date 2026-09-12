#!/bin/sh
# main native 二进制冒烟（测试计划 P4-2）：
# build-native.sh 内置 8s 冒烟不带 POSTGRES_PASS，连库失败报
# "Unable to determine Dialect" 属已知假象（native-build skill §三案底），
# 本脚本带真实库口令重跑启动冒烟并给出明确退出码。
# 用法: sh run-native-smoke.sh
set -e
cd "$(dirname "$0")/stock-calculator-main"
PASS=$(grep -E '^POSTGRES_PASSWORD=' ../.env | cut -d= -f2-)
export POSTGRES_PASS="$PASS"
export POSTGRES_URL="jdbc:postgresql://localhost/scs"
export POSTGRES_USER="root"
timeout --kill-after=3 20 ./target/stock-calculator-service --server.port=19999 > /tmp/ni-run2.log 2>&1 || true
pkill -9 -f 'target/stock-calculator-service --server.port=19999' 2>/dev/null || true
grep -E 'Tomcat started|Started StockCalculator' /tmp/ni-run2.log
echo "SMOKE_OK: native main started and bound port 19999"
