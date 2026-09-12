#!/bin/sh
# 全量回归（排除 TaskServiceTest：打真实 CLS API 必挂）
# 红线：不 source .env 整体注入——只取 POSTGRES_PASSWORD 转 POSTGRES_PASS（本地库），
# CLOUDFLARE_* 不注入 → CfEmbeddingSmokeLiveTest 自动 skip，绝不真调 CF / 不碰远程库
set -e
cd "$(dirname "$0")"
PASS=$(grep -E '^POSTGRES_PASSWORD=' .env | cut -d= -f2-)
export POSTGRES_PASS="$PASS"
export POSTGRES_URL="jdbc:postgresql://localhost/scs"
export POSTGRES_USER="root"
./mvnw install -Dtest='!TaskServiceTest' -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false
