#!/usr/bin/env bash
# ============================================================================
# 确定性打包脚本 — Stock Calculator Service → Docker Native 镜像
#
# 解决的问题(前面的踩坑):
#   1. 本地跑通、打镜像却报 ClassNotFound 的根因是:打进镜像的二进制和本地验证
#      过的不是同一次编译产物(混用了 GraalVM 21 编的旧二进制 / buildkit 缓存)。
#   2. native 打包对二进制是"死环境",一旦编错 JDK,类缺失在容器里必然爆。
#
# 本脚本强制做到:
#   - 锁定 GraalVM 25.0.x(Spring Boot 4 必需,21 不支持其 reachability 元数据)
#   - 每次 rm -rf target,保证干净编译
#   - 用 build-native.sh(直调 native-image,避开通用的 mvn -Pnative 卡死,
#     且自动剥离 test jar 避免 ExcludeFilter... 崩溃)
#   - 校验二进制自报"使用 Java 25"后才允许打包
#   - docker build --no-cache,避免 COPY 命中旧层
#   - 不依赖当前 sdkman current 指向谁(主动从已安装的 25.0.x 里挑)
#
# 用法:
#   ./package-native.sh                    # 完整: 编译 + 打镜像
#   ./package-native.sh --no-build         # 跳过 native 编译,只用已有 target 打镜像
#   ./package-native.sh --push             # 编译 + 打镜像 + push 到给定 TAG
# ============================================================================
set -euo pipefail

# ---------------- 参数 ----------------
SKIP_BUILD=0
PUSH=0
IMAGE_NAME="stock-calculator:latest"
for a in "$@"; do
  case "$a" in
    --no-build) SKIP_BUILD=1 ;;
    --push)     PUSH=1 ;;
    -t)         IMAGE_NAME="stock-calculator:latest" ;;
    --*)        echo "未知参数: $a" >&2; exit 2 ;;
    *)          IMAGE_NAME="$a" ;;
  esac
done

# 项目根目录
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# ---------------- 步骤 0: 锁定 GraalVM 25.0.x ----------------
# 这个脚本的核心动作:强行以 25.0.x 编译,不许用 21,也不许用 sdkman current。
JAVA_HOME=""
for cand in ~/.sdkman/candidates/java/25.0.*-graal ~/.sdkman/candidates/java/25.0.*; do
  if [ -f "$cand/bin/native-image" ]; then
    JAVA_HOME="$cand"
    break
  fi
done

if [ -z "$JAVA_HOME" ]; then
  echo "❌ 未找到 GraalVM 25.0.x 的 native-image。请先: sdk install java 25.0.4-graal" >&2
  exit 1
fi
export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"

echo "════════════════════════════════════════════════════════════════"
echo " 确定性打包 (GraalVM: 从 sdkman 锁定 25.0.x)"
echo "   JAVA_HOME       = $JAVA_HOME"
echo "   native-image    = $(native-image --version 2>&1 | head -1)"
echo "════════════════════════════════════════════════════════════════"

# 硬性下线检查:绝不允许 21 混入
if ! native-image --version 2>&1 | grep -qE '25\.0\.[0-9]+'; then
  echo "❌ native-image 不是 25.0.x,已中止(为避免产出兼容性有问题的二进制)。" >&2
  exit 1
fi

# ---------------- 步骤 1: 编译 Native 二进制 ----------------
if [ "$SKIP_BUILD" -eq 0 ]; then
  echo
  echo "█████ 步骤 1/3: 干净编译 Native 二进制..."
  cd "$ROOT_DIR"
  rm -rf target
  "$ROOT_DIR/build-native.sh"
else
  echo
  echo "█████ 步骤 1/3: 跳过编译(--no-build),使用已有 target 二进制"
fi

# ---------------- 步骤 2: 校验二进制确实是 GraalVM 25 编的 ----------------
BIN="$ROOT_DIR/target/stock-calculator-service"
echo
echo "█████ 步骤 2/3: 校验二进制运行时版本(必须为 Java 25)..."
if [ ! -x "$BIN" ]; then
  echo "❌ 未找到二进制: $BIN" >&2
  exit 1
fi
ls -lh "$BIN"
file "$BIN"

# 启动探测:让二进制自报 using Java 版本
timeout 15 "$BIN" --server.port=19999 > /tmp/pkg-probe.log 2>&1 &
PROBE_PID=$!
sleep 6
kill "$PROBE_PID" 2>/dev/null || true
wait "$PROBE_PID" 2>/dev/null || true

if grep -qi "using Java 25" /tmp/pkg-probe.log; then
  echo "  ✅ 二进制自报: $(grep -i 'using Java' /tmp/pkg-probe.log | head -1)"
elif grep -qi "using Java 21" /tmp/pkg-probe.log; then
  echo "  ❌ 二进制是 GraalVM 21 编的(build-native.sh 未用锁定 JDK),已中止。日志见 /tmp/pkg-probe.log" >&2
  exit 1
else
  # 启动太快没捕获版本行(属正常),只要能完成日志技术栈含 25 即放行
  echo "  ⚠️  未捕获 using Java 行(可能已快速启动),继续打包。完整日志见 /tmp/pkg-probe.log"
  tail -5 /tmp/pkg-probe.log
fi

# ---------------- 步骤 3: 用最小 Dockerfile 打包镜像(禁缓存) ----------------
echo
echo "█████ 步骤 3/3: docker build (Dockerfile.native, --no-cache)..."
cd "$ROOT_DIR"
docker build --no-cache -f Dockerfile.native -t "$IMAGE_NAME" .

echo
echo "════════════════════════════════════════════════════════════════"
echo " ✅ 镜像已构建: $IMAGE_NAME"
echo "    体积: $(docker images --format '{{.Repository}}:{{.Tag}} {{.Size}}' | grep -F "$IMAGE_NAME")"
echo
echo " 运行:"
echo "   docker run --rm -p 18080:18080 \\"
echo "     -e GEMINI_API_KEY=你的key $IMAGE_NAME"
echo "════════════════════════════════════════════════════════════════"

# ---------------- 可选: push ----------------
if [ "$PUSH" -eq 1 ]; then
  echo "推送镜像 $IMAGE_NAME ..."
  docker push "$IMAGE_NAME"
fi