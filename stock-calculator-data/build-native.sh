#!/usr/bin/env bash
# ============================================================================
# Stock Calculator Data Service — GraalVM Native 直接编译脚本
#
# 参照 stock-calculator-main/build-native.sh（已验证套路），适配点：
#   1. 依赖 contract 模块，先 install 父 POM + contract 到 ~/.m2
#   2. 无 native profile / 无 JPA 数据源（不需要 PostgreSQL）
#   3. 条件装配（collector/worker/ingest）在构建期钉死：AOT 固化条件评估，
#      运行期不可再切换角色（R1 教训）。VARIANT 选择产出变体：
#        all   = all-in-one（三角色全开，现网主机形态，collector 副本恒=1）
#        worker = 仅 worker（两域全开），collector/ingest 物理裁剪 + 无 web——
#                 多副本扩容镜像（新机器拉 -data-worker tag 跑 N 副本竞争消费）。
#                 已裁剪角色运行期 env 强开无效（AOT 期 bean 不存在）。
#   4. process-aot 会实例化全部单例 → worker 的 CF/LLM fail-fast 在构建期就会触发，
#      须注入 dummy 凭据（运行期值仍从环境变量取）。
#
# 用法:
#   ./build-native.sh            # 完整构建（install contract + compile + AOT + native-image + 冒烟）
#   ./build-native.sh --no-pkg   # 跳过 maven 编译，复用已有 target/ 产物
#   VARIANT=worker ./build-native.sh   # 构建 worker-only 变体（二进制 -worker 后缀）
# ============================================================================
set -e
cd "$(dirname "$0")"   # 进入 stock-calculator-data/

# ---------------- 变体选择（VARIANT env，默认 all） ----------------
if [ -z "$VARIANT" ]; then
  VARIANT="all"
fi
case "$VARIANT" in
  all)    BINARY_NAME="stock-calculator-data-service" ;;
  worker) BINARY_NAME="stock-calculator-data-service-worker" ;;
  *) echo "❌ VARIANT 仅支持 all|worker：$VARIANT" >&2; exit 1 ;;
esac

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
echo "   VARIANT      = $VARIANT"
echo "   native-image = $(native-image --version 2>&1 | head -1)"
echo "════════════════════════════════════════════════════════════════"

# 构建期钉死角色开关（AOT 固化条件评估）+ dummy 凭据（process-aot 实例化单例，
# fail-fast 校验构建期触发；运行期值仍可经环境变量覆盖）。worker 变体同时钉
# web-application-type=none：starter-web 是无条件依赖，不钉则 Tomcat 照启
# （R1 ingest 坑的镜像面），钉死后 servlet 栈被 DCE 从二进制剔除
if [ "$VARIANT" = "worker" ]; then
  export SPRING_APPLICATION_JSON='{
    "spring": {"main": {"web-application-type": "none"}},
    "datasvc": {
      "collector": {"enabled": false, "announcement": {"enabled": false}},
      "worker": {"enabled": true,
                 "embedding": {"account-id": "build-time-dummy", "api-token": "build-time-dummy"}},
      "ingest": {"enabled": false},
      "llm": {"base-url": "http://build-time.invalid", "api-key": "build-time-dummy", "model": "build-time-dummy"}
    }
  }'
else
  export SPRING_APPLICATION_JSON='{
    "datasvc": {
      "collector": {"enabled": true, "announcement": {"enabled": true}},
      "worker": {"enabled": true,
                 "embedding": {"account-id": "build-time-dummy", "api-token": "build-time-dummy"}},
      "ingest": {"enabled": true, "secret": "build-time-dummy"},
      "llm": {"base-url": "http://build-time.invalid", "api-key": "build-time-dummy", "model": "build-time-dummy"}
    }
  }'
fi

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
# || [ -n "$j" ]：cp.txt 末行无换行符时 read 会静默丢弃最后一个 jar
while IFS= read -r j || [ -n "$j" ]; do
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
  -o "target/$BINARY_NAME" \
  -H:NumberOfThreads=8 \
  > /tmp/ni-data-build.log 2>&1; then
  echo "❌ native-image 编译失败，日志末尾 60 行："
  tail -60 /tmp/ni-data-build.log
  exit 1
fi

# 剥离 DWARF 调试段（GraalVM 默认编入，大应用可占二进制 30~50%）：只影响 gdb
# 符号化，不影响运行；后续冒烟与镜像打包用的都是剥离后的最终产物
if command -v objcopy >/dev/null 2>&1; then
  echo "█████ 剥离调试符号（objcopy --strip-debug）..."
  BEFORE_SIZE="$(du -h "target/$BINARY_NAME" | cut -f1)"
  objcopy --strip-debug "target/$BINARY_NAME"
  echo "   二进制体积：剥离前 $BEFORE_SIZE → 剥离后 $(du -h "target/$BINARY_NAME" | cut -f1)"
else
  echo "⚠️ 未找到 objcopy，跳过调试符号剥离（不影响产物正确性）"
fi

echo "█████ 步骤 4/4: 编译完成"
ls -lh "target/$BINARY_NAME"
file "target/$BINARY_NAME"

echo "==================== 启动冒烟（复用 smoke-native.sh） ===================="
# 注意：SPRING_APPLICATION_JSON（本脚本导出的构建期同款 dummy 凭据）在冒烟时
# 仍然生效——运行期实例化单例同样会触发 CF/LLM fail-fast，须有非空值；
# VARIANT 透传给冒烟脚本（worker 变体无 HTTP 端点，探活策略不同）
VARIANT="$VARIANT" bash smoke-native.sh
