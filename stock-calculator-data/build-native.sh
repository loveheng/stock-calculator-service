#!/usr/bin/env bash
# ============================================================================
# Stock Calculator Data Service — GraalVM Native 直接编译脚本
#
# 参照 stock-calculator-main/build-native.sh（已验证套路），适配点：
#   1. 依赖 contract 模块，先 install 父 POM + contract 到 ~/.m2
#   2. 无 native profile / 无 JPA 数据源（不需要 PostgreSQL）
#   3. 条件装配（collector/worker/ingest）在构建期钉死：AOT 固化条件评估，
#      运行期不可再切换角色（R1 教训）。本脚本产 all-in-one 变体（三角色全开），
#      满足 R1 PDFBox AOT 冒烟；worker-only 拆分变体见部署文档。
#   4. process-aot 会实例化全部单例 → worker 的 CF/LLM fail-fast 在构建期就会触发，
#      须注入 dummy 凭据（运行期值仍从环境变量取）。
#
# 用法:
#   ./build-native.sh            # 完整构建（install contract + compile + AOT + native-image + 冒烟）
#   ./build-native.sh --no-pkg   # 跳过 maven 编译，复用已有 target/ 产物
# ============================================================================
set -e
cd "$(dirname "$0")"   # 进入 stock-calculator-data/

# ---------------- 步骤 0: 锁定 GraalVM 25.0.x（与 main 同套路） ----------------
GRAALVM_HOME=""
if [ -x "/opt/GraalVM25/bin/native-image" ]; then
  GRAALVM_HOME="/opt/GraalVM25"
elif [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/native-image" ]; then
  GRAALVM_HOME="$JAVA_HOME"
elif command -v native-image >/dev/null 2>&1; then
  GRAALVM_HOME=""
else
  echo "❌ 未找到 native-image（/opt/GraalVM25 或 JAVA_HOME 或 PATH）" >&2
  exit 1
fi
if [ -n "$GRAALVM_HOME" ]; then
  export JAVA_HOME="$GRAALVM_HOME"
  export PATH="$GRAALVM_HOME/bin:$PATH"
fi
if ! native-image --version 2>&1 | grep -qE '25\.[0-9]+'; then
  echo "❌ native-image 不是 GraalVM 25.x" >&2
  exit 1
fi
JVER="$(java -version 2>&1 | head -1)"
if ! echo "$JVER" | grep -qE 'version "25\.'; then
  echo "❌ 当前 java 不是 JDK 25：$JVER" >&2
  exit 1
fi

echo "════════════════════════════════════════════════════════════════"
echo " Data Service GraalVM Native 编译"
echo "   native-image = $(native-image --version 2>&1 | head -1)"
echo "════════════════════════════════════════════════════════════════"

# 构建期钉死角色开关（AOT 固化条件评估）+ dummy 凭据（process-aot 实例化单例，
# fail-fast 校验构建期触发；运行期值仍可经环境变量覆盖）
export SPRING_APPLICATION_JSON='{
  "datasvc": {
    "collector": {"enabled": true, "announcement": {"enabled": true}},
    "worker": {"enabled": true,
               "embedding": {"account-id": "build-time-dummy", "api-token": "build-time-dummy"}},
    "ingest": {"enabled": true, "secret": "build-time-dummy"},
    "llm": {"base-url": "http://build-time.invalid", "api-key": "build-time-dummy", "model": "build-time-dummy"}
  }
}'

SKIP_PKG=${1:-}

if [ "$SKIP_PKG" != "--no-pkg" ]; then
  echo "█████ 步骤 0/4: install 父 POM + contract（data 单独编译的解析前提）..."
  ../mvnw -f .. install -N -q -DskipTests
  ../mvnw -f .. install -pl stock-calculator-contract -q -DskipTests

  echo "█████ 步骤 1/4: Maven compile + AOT 处理..."
  ../mvnw -DskipTests compile spring-boot:process-aot -q -Dfile.encoding=UTF-8 \
    -Dorg.slf4j.simpleLogger.log.org.apache.maven.cli.transfer.Slf4jMavenTransferListener=warn
else
  echo "█████ 步骤 0-1/4: 跳过 Maven，复用已有 target/ 产物"
fi

# AOT 产物校验：classes + resources 必须同时存在（缺 = process-aot 未执行）
if [ ! -f target/spring-aot/main/classes/com/zzh/stock_calculator/data/DataServiceApplication__ApplicationContextInitializer.class ]; then
  echo "❌ AOT 产物缺失（target/spring-aot/main/classes）" >&2
  exit 1
fi

echo "█████ 步骤 2/4: 生成依赖 classpath 并剥离 test jar..."
../mvnw -q dependency:build-classpath -Dmdep.outputFile=target/cp.txt -Dfile.encoding=UTF-8 >/dev/null 2>&1
RAW_CP=$(cat target/cp.txt)
STRIPPED=""
while IFS= read -r j; do
  case "$j" in
    *spring-boot-starter-test*|*spring-boot-test*|*junit*|*mockito*|*assertj*|*hamcrest*|*opentest4j*|*spring-test*|*json-path*|*json-smart*|*jsonassert*|*xmlunit*|*awaitility*|*-test-*|*resttestclient*)
      continue ;;
  esac
  STRIPPED="$STRIPPED:$j"
done < <(printf '%s' "$RAW_CP" | tr ':' '\n')
CP="${STRIPPED#:}:target/classes:target/spring-aot/main/classes:target/spring-aot/main/resources"
if [ -z "$CP" ] || [ "$CP" = ":target/classes:target/spring-aot/main/classes:target/spring-aot/main/resources" ]; then
  echo "❌ classpath 为空，检查 target/cp.txt" >&2
  exit 1
fi
echo "        classpath jar 数量: $(printf '%s' "$CP" | tr ':' '\n' | grep -c '\.jar$')"

echo "█████ 步骤 3/4: native-image（约 8~15 分钟，日志 /tmp/ni-data-build.log）..."
if ! native-image \
  -cp "$CP" \
  -H:Class=com.zzh.stock_calculator.data.DataServiceApplication \
  --no-fallback \
  -J-Xmx12g \
  --enable-all-security-services \
  -H:+AddAllCharsets \
  -H:EnableURLProtocols=https \
  -H:+ReportUnsupportedElementsAtRuntime \
  --install-exit-handlers \
  --initialize-at-build-time=ch.qos.logback.classic,ch.qos.logback.core,org.slf4j,org.jboss.logging \
  -o target/stock-calculator-data-service \
  -H:NumberOfThreads=8 \
  > /tmp/ni-data-build.log 2>&1; then
  echo "❌ native-image 编译失败，日志末尾 60 行："
  tail -60 /tmp/ni-data-build.log
  exit 1
fi

echo "█████ 步骤 4/4: 编译完成"
ls -lh target/stock-calculator-data-service
file target/stock-calculator-data-service

echo "==================== 启动冒烟（复用 smoke-native.sh） ===================="
# 注意：SPRING_APPLICATION_JSON（本脚本顶部导出的构建期同款 dummy 凭据）在冒烟时
# 仍然生效——运行期实例化单例同样会触发 CF/LLM fail-fast，须有非空值
bash smoke-native.sh
