#!/usr/bin/env bash
# ============================================================================
# 录制 main 模块 native 运行期 reachability metadata（tracing agent）
#
# 用法:
#   POSTGRES_PASS=... bash record-agent.sh [录制秒数, 默认 75]
#
# 说明:
#   - agent 录制必须用 /opt/GraalVM25/bin/java（系统 java 没有 agent 库）
#   - --spring.main.lazy-initialization=false 让全部单例（含 Spring AI
#     OpenAiChatModel、auth 拦截器、爬虫任务）在录制期完成装配并被记录
#   - SIGTERM 优雅停机, agent 在 JVM 退出时把配置写入 agent-config/
#   - 录制完成后 gen-logger-config.py 会把 agent-config/reachability-metadata.json
#     与生成器条目合并为单一配置
# ============================================================================
set -e
cd "$(dirname "$0")"   # stock-calculator-main/

SEC=${1:-75}

# ---------------- 1. 生成依赖 classpath 并剥离 test jar（与 build-native.sh 同规则）----------------
../mvnw -q dependency:build-classpath -Dmdep.outputFile=target/cp.txt -Dfile.encoding=UTF-8
RAW_CP=$(cat target/cp.txt)
STRIPPED=""
while IFS= read -r j; do
  case "$j" in
    *spring-boot-starter-test*|*spring-boot-test*|*junit*|*mockito*|*assertj*|*hamcrest*|*opentest4j*|*spring-test*|*json-path*|*json-smart*|*jsonassert*|*xmlunit*|*awaitility*|*-test-*|*resttestclient*)
      continue ;;
  esac
  STRIPPED="$STRIPPED:$j"
done < <(printf '%s' "$RAW_CP" | tr ':' '\n')
CP="${STRIPPED#:}:target/classes"
echo "classpath jar 数量: $(printf '%s' "$CP" | tr ':' '\n' | grep -c '\.jar$')"

# ---------------- 2. 以 tracing agent 运行应用 ----------------
rm -rf agent-config
mkdir -p agent-config

/opt/GraalVM25/bin/java \
  -agentlib:native-image-agent=config-output-dir=agent-config \
  -cp "$CP" com.zzh.stock_calculator.StockCalculatorApplication \
  --server.port=19998 \
  --spring.main.lazy-initialization=false \
  > /tmp/ni-record.log 2>&1 &
PID=$!
echo "录制中 pid=$PID, 时长 ${SEC}s（爬虫补录约在启动后 10s 触发, 请耐心等待）..."

sleep "$SEC"
kill -TERM "$PID" 2>/dev/null || true
wait "$PID" 2>/dev/null || true

echo "---- 录制日志末尾 25 行 ----"
tail -25 /tmp/ni-record.log || true
echo "---- agent-config 产物 ----"
ls -la agent-config
if [ -f agent-config/reachability-metadata.json ]; then
  echo "✅ 录制成功"
else
  echo "❌ 未生成 reachability-metadata.json, 检查上方日志"
  exit 1
fi
