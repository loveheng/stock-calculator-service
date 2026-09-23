#!/bin/bash
# 临时复现脚本：在 scs-net 内以 JVM 模式启动 orchestration（无 mcp 经纪人，模拟 CI runner）
set -a
. ../.env
set +a
export POSTGRES_URL_MCP=jdbc:postgresql://10.89.2.12:5432/stock_mcp
export RABBIT_HOST=10.89.2.14
export SPRING_AI_MCP_CLIENT_INITIALIZED=false
CP="target/classes:$(cat target/cp.txt)"
exec /opt/GraalVM25/bin/java -cp "$CP" com.zzh.stock_calculator.orchestration.OrchestrationApplication
