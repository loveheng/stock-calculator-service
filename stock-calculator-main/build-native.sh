#!/usr/bin/env bash
# ============================================================================
# Stock Calculator Service — GraalVM Native 直接编译脚本 (单模块)
#
# 单模块: stock-calculator-main（2026-09 与 common 合并后为唯一模块）
#
# 环境要求（不再依赖 sdkman）:
#   1. GraalVM 25.0.x + native-image，来源按以下顺序探测：
#      a) JAVA_HOME 环境变量指向 GraalVM 25
#      b) /opt/GraalVM25 (本机默认安装位置)
#      c) PATH 上的 native-image
#   2. Maven 统一使用仓库自带 ../mvnw，不依赖系统 mvn / sdkman
#
# 多模块注意:
#   common 模块必须先 install 到本地仓库 (~/.m2)，main 模块单独编译时
#   才能解析到 com.zzh:stock-calculator-common:0.0.1-SNAPSHOT。
#
# 用法:
#   ./build-native.sh            # 完整构建（install common + compile + AOT + native-image）
#   ./build-native.sh --no-pkg   # 跳过 maven 编译，直接用已有 target/ 产物编译
#
# 注意：绕过 native-maven-plugin 的卡死问题，直接调用 native-image。
#       完整编译需 8~15 分钟，请在终端认真等待，或使用 nohup 后台运行。
# ============================================================================
set -e
cd "$(dirname "$0")"   # 进入 stock-calculator-main/

# ---------------- 步骤 0: 锁定 GraalVM 25.0.x ----------------
GRAALVM_HOME=""
if [ -x "/opt/GraalVM25/bin/native-image" ]; then
  GRAALVM_HOME="/opt/GraalVM25"
elif [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/native-image" ]; then
  GRAALVM_HOME="$JAVA_HOME"
elif command -v native-image >/dev/null 2>&1; then
  GRAALVM_HOME=""   # native-image 已在 PATH 上，直接用
else
  echo "❌ 未找到 native-image。请安装 GraalVM 25.0.x（本机默认位置 /opt/GraalVM25），" >&2
  echo "   或设置 JAVA_HOME 指向 GraalVM 25，或将 native-image 加入 PATH。" >&2
  exit 1
fi

if [ -n "$GRAALVM_HOME" ]; then
  export JAVA_HOME="$GRAALVM_HOME"
  export PATH="$GRAALVM_HOME/bin:$PATH"
fi

NI_VERSION="$(native-image --version 2>&1 | head -1)"
echo "════════════════════════════════════════════════════════════════"
echo " GraalVM Native 编译 (不依赖 sdkman)"
echo "   JAVA_HOME    = ${JAVA_HOME:-来自 PATH 的 native-image}"
echo "   native-image = $NI_VERSION"
echo "════════════════════════════════════════════════════════════════"

# 硬性版本检查：必须是 GraalVM 25.x（Spring Boot 4 的 reachability 元数据需要）
if ! native-image --version 2>&1 | grep -qE '25\.[0-9]+'; then
  echo "❌ native-image 不是 GraalVM 25.x：$NI_VERSION" >&2
  echo "   请将 JAVA_HOME 指向 /opt/GraalVM25 或安装 GraalVM 25。" >&2
  exit 1
fi

# 硬性 JDK 检查：maven 编译用的 java 也必须是 25（native-image 要求与运行 JDK 同源）
JVER="$(java -version 2>&1 | head -1)"
if ! echo "$JVER" | grep -qE 'version "25\.'; then
  echo "❌ 当前 java 不是 JDK 25：$JVER（已导出 GRAALVM 的 JAVA_HOME 后仍不对，请检查环境）" >&2
  exit 1
fi

SKIP_PKG=${1:-}

echo "████████ 开始 GraalVM Native 编译"

# ---------------- 步骤 1: Maven compile + AOT ----------------
if [ "$SKIP_PKG" != "--no-pkg" ]; then
  echo "█████ 步骤 1/4: Maven compile + AOT 处理..."
  # 只编译不 package，避免触发 native-maven-plugin 卡死问题。
  # process-aot 显式调用（它会生成并编译 AOT 类到 target/spring-aot/main/classes），
  # 不依赖 lifecycle phase 绑定，行为确定。
  ../mvnw -Pnative -DskipTests compile spring-boot:process-aot -q -Dfile.encoding=UTF-8 \
    -Dorg.slf4j.simpleLogger.log.org.apache.maven.cli.transfer.Slf4jMavenTransferListener=warn
else
  echo "█████ 步骤 1/4: 跳过 Maven，复用已有 target/ 产物"
fi

# ---------------- 步骤 2: 生成依赖 classpath 并剥离 test jar ----------------
echo "█████ 步骤 2/4: 生成依赖 classpath..."
# 注：test 相关 jar 必须剥离，否则 spring-boot-test 的 spring.factories
#    (ExcludeFilterApplicationContextInitializer) 会进入镜像导致运行时 ClassNotFoundException。
../mvnw -q dependency:build-classpath -Dmdep.outputFile=target/cp.txt -Dfile.encoding=UTF-8 >/dev/null 2>&1
RAW_CP=$(cat target/cp.txt)
STRIPPED=""
while IFS= read -r j; do
  case "$j" in
    # *-test-* 兜底 Boot 4 新式 test jar（如 spring-boot-data-jpa-test / spring-boot-restclient-test），
    # 否则会泄入 native classpath，spring.factories 触发运行期 ClassNotFoundException
    *spring-boot-starter-test*|*spring-boot-test*|*junit*|*mockito*|*assertj*|*hamcrest*|*opentest4j*|*spring-test*|*json-path*|*json-smart*|*jsonassert*|*xmlunit*|*awaitility*|*-test-*|*resttestclient*)
      continue ;;
  esac
  STRIPPED="$STRIPPED:$j"
done < <(printf '%s' "$RAW_CP" | tr ':' '\n')
CP="${STRIPPED#:}:target/classes:target/spring-aot/main/classes:target/spring-aot/main/resources"
if [ -z "$CP" ] || [ "$CP" = ":target/classes:target/spring-aot/main/classes:target/spring-aot/main/resources" ]; then
  echo "❌ 错误：classpath 为空，dependency:build-classpath 可能失败。请检查 target/cp.txt。"
  exit 1
fi
echo "        classpath jar 数量: $(printf '%s' "$CP" | tr ':' '\n' | grep -c '\.jar$')"

# jboss-logging 生成的消息 logger 类（XxxLogger_$logger）运行时通过
# MethodHandles.Lookup.findClass 按计算出的类名动态加载，静态可达性分析发现不了，
# 必须显式注册进反射配置，否则启动报:
#   Invalid logger interface Xxx (implementation not found)
python3 gen-logger-config.py
if [ ! -f target/classes/META-INF/native-image/com.zzh/ni-logger-config/reachability-metadata.json ]; then
  echo "❌ 生成 jboss-logging logger 反射配置失败（需要 python3）" >&2
  exit 1
fi

echo "█████ 步骤 3/4: 运行 native-image (此步耗时最长，约 8~15 分钟，请耐心等待)..."
echo "        注意：native-image 长时间无输出属正常现象，请勿中断！"

# 写日志到 /tmp/ni-build.log，失败时自动展示末尾日志并退出非零（CI 友好）
# 173-jar Spring AI 变体分析规模大, 7g 曾 OOM, 12g 稳妥
if ! native-image \
  -cp "$CP" \
  -H:Class=com.zzh.stock_calculator.StockCalculatorApplication \
  --no-fallback \
  -J-Xmx12g \
  --enable-all-security-services \
  -H:+AddAllCharsets \
  -H:EnableURLProtocols=https \
  -H:+ReportUnsupportedElementsAtRuntime \
  --initialize-at-build-time=ch.qos.logback.classic,ch.qos.logback.core,org.slf4j,org.jboss.logging,net.bytebuddy \
  -o target/stock-calculator-service \
  -H:NumberOfThreads=8 \
  > /tmp/ni-build.log 2>&1; then
  echo "❌ native-image 编译失败，日志末尾 60 行："
  tail -60 /tmp/ni-build.log
  exit 1
fi

echo "█████ 步骤 4/4: Native 编译完成！"

echo ""
echo "==================== 验证 ===================="
ls -lh target/stock-calculator-service
file target/stock-calculator-service

echo ""
echo "==================== 启动测试 (8 秒) ===================="
# 启动测试（8 秒）：能打印 Tomcat started 才算构建成功，失败直接退出非零
timeout 8 ./target/stock-calculator-service --server.port=19999 > /tmp/ni-run.log 2>&1 || true
if grep -qE 'Tomcat started|Started StockCalculator' /tmp/ni-run.log; then
  echo "✅ 启动测试通过！二进制文件: target/stock-calculator-service"
  echo "   （AOT 模式，启动通常 < 1 秒；完整启动日志: /tmp/ni-run.log）"
else
  echo "❌ 启动测试失败（未捕获 Tomcat started，日志末尾 20 行）："
  tail -20 /tmp/ni-run.log || true
  exit 1
fi
