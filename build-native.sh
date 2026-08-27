#!/usr/bin/env bash
# ============================================================================
# Stock Calculator Service — GraalVM Native 直接编译脚本
# 绕过 native-maven-plugin:1.1.8 的 compile-no-fork 卡死问题，直接调用 native-image。
# 完整编译需 10+ 分钟，请在终端认真等待，或使用 nohup 后台运行。
#
# 用法:
#   ./build-native.sh            # 完整构建（compile + AOT + native-image）
#   ./build-native.sh --no-pkg   # 跳过 maven compile/package，直接用已有 target/ 编译
# ============================================================================
set -e
source ~/.sdkman/bin/sdkman-init.sh

cd "$(dirname "$0")"

echo "████████ 开始 GraalVM Native 编译 (GraalVM: $(native-image --version | head -1))"

# 1. 生成 classpath（若未提供 --no-pkg 则同时跑 compile + AOT）
SKIP_PKG=${1:-}

if [ "$SKIP_PKG" != "--no-pkg" ]; then
  echo "█████ 步骤 1/4: Maven compile + AOT 处理..."
  # 注意：只执行到 process-classes（compile + process-aot + compile-after-aot），
  #      不执行 package，避免触发 native-maven-plugin 在 package 阶段的
  #      compile-no-fork 卡死问题。真正的 native-image 由步骤 3 直接调用完成。
  mvn -Pnative -DskipTests compile process-classes -q -Dfile.encoding=UTF-8 \
    -Dorg.slf4j.simpleLogger.log.org.apache.maven.cli.transfer.Slf4jMavenTransferListener=warn
else
  echo "█████ 步骤 1/4: 跳过 Maven，复用已有 target/o 产物"
fi

echo "█████ 步骤 2/4: 生成依赖 classpath..."
# dependency:build-classpath 的 includeScope 参数在此环境实测未生效（classpath 仍含 test jar），
# 因此改为显式剥离所有 test 相关 jar：
# 这会剔除 spring-boot-test(-autoconfigure)、junit、mockito、assertj 等，
# 从而确保 spring-boot-test 的 spring.factories（声明了 ExcludeFilterApplicationContextInitializer）绝不会进入 native 镜像。
mvn -Pnative -o dependency:build-classpath -Dmdep.outputFile=/tmp/cp.txt -q -Dfile.encoding=UTF-8 >/dev/null 2>&1
RAW_CP=$(cat /tmp/cp.txt)
STRIPPED=""
while IFS= read -r j; do
  case "$j" in
    *spring-boot-starter-test*|*spring-boot-test*|*junit*|*mockito*|*assertj*|*hamcrest*|*opentest4j*|*spring-test*|*json-path*|*json-smart*|*jsonassert*|*xmlunit*|*awaitility*)
      continue ;;
  esac
  STRIPPED="$STRIPPED:$j"
done < <(printf '%s' "$RAW_CP" | tr ':' '\n')
CP="${STRIPPED#:}:target/classes:target/spring-aot/main/resources"
if [ -z "$CP" ] || [ "$CP" = ":target/classes:target/spring-aot/main/resources" ]; then
  echo "❌ 错误：classpath 为空，dependency:build-classpath 可能失败。请检查 /tmp/cp.txt。"
  exit 1
fi
echo "        classpath jar 数量: $(printf '%s' "$CP" | tr ':' '\n' | grep -c '\.jar$')"

echo "█████ 步骤 3/4: 运行 native-image (此步耗时最长，约 8~15 分钟，请耐心等待)..."
echo "        注意：native-image 是后台进程，长时间无输出属正常现象，请勿中断！"

# 写日志到文件，同时 tee 到终端（仅显示最后进度）
native-image \
  -cp "$CP" \
  -H:Class=com.zzh.stock_calculator.StockCalculatorApplication \
  --no-fallback \
  -J-Xmx7g \
  --enable-all-security-services \
  -H:+AddAllCharsets \
  -H:EnableURLProtocols=https \
  -H:+ReportUnsupportedElementsAtRuntime \
  --initialize-at-build-time=ch.qos.logback.classic,ch.qos.logback.core,org.slf4j \
  -o target/stock-calculator-service \
  -H:NumberOfThreads=8 \
  > /tmp/ni-build.log 2>&1

echo "█████ 步骤 4/4: Native 编译完成！"

echo ""
echo "==================== 验证 ===================="
ls -lh target/stock-calculator-service
file target/stock-calculator-service

echo ""
echo "==================== 启动测试 (5 秒) ===================="
timeout 5 ./target/stock-calculator-service --server.port=19999 > /tmp/ni-run.log 2>&1 || true
echo "（启动日志见 /tmp/ni-run.log）"
echo ""
echo "✅ 编译成功！二进制文件: target/stock-calculator-service"
