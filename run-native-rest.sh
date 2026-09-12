#!/bin/sh
# main native REST 门禁冒烟（测试计划 P4-2）：带库口令复用 smoke-curl.sh
# 验证 admin token 门禁 + ApiResponse 信封
# 用法: sh run-native-rest.sh
set -e
cd "$(dirname "$0")/stock-calculator-main"
PASS=$(grep -E '^POSTGRES_PASSWORD=' ../.env | cut -d= -f2-)
export POSTGRES_PASS="$PASS"
export POSTGRES_URL="jdbc:postgresql://localhost/scs"
export POSTGRES_USER="root"
sh smoke-curl.sh
