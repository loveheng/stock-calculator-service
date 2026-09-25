#!/bin/sh
# GraalVM 官方 reachability-metadata 同步工具：clone/pull 官方仓库 → 同步指定库版本
# 目录到 third_party/graalvm-reachability-metadata/ → 校验各 build-native.sh 引用
# 的版本路径存在。升级 HikariCP/hibernate 后跑一次即可；幂等可重跑。
#
# toolbox-script
# format: v1
# name: update-graalvm-metadata
# summary: 同步官方 GraalVM reachability-metadata 到 third_party 并校验脚本引用
# trigger: manual
# cat: build
# alias: gm
# platform: unix

set -e

REPO_URL="https://github.com/oracle/graalvm-reachability-metadata.git"
CACHE_DIR="/tmp/graalvm-meta"
LIBS="com.zaxxer org.hibernate.orm"

usage() {
  cat <<'EOF'
用法: sh scripts/agent-tools/update-graalvm-metadata.sh [--json|--help] [check]
  裸跑    同步官方元数据到 third_party/graalvm-reachability-metadata/（需网络）
  check   仅本地校验（不联网）：third_party 目录与各 build-native.sh 引用路径存在性
  --json  快速预检结论（不联网，等同 check 单行 JSON）
  --help  本帮助
裸跑与 --json 的区别：裸跑执行网络同步任务本体；--json 只做本地快速预检。
EOF
}

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
TP="$ROOT/third_party/graalvm-reachability-metadata"

# 本地校验：目录存在 + 各 build-native.sh 引用的版本路径在 third_party 下存在
do_check() {
  [ -d "$TP" ] || { MSG="third_party/graalvm-reachability-metadata 不存在（先裸跑同步）"; return 1; }
  MISSING=""
  for m in stock-calculator-main stock-calculator-data stock-calculator-mcp \
           stock-calculator-mcp-notify stock-calculator-orchestration; do
    REF=$(grep -o 'ConfigurationFileDirectories=[^ \\]*' "$ROOT/$m/build-native.sh" 2>/dev/null | head -1 | cut -d= -f2)
    [ -n "$REF" ] || continue
    echo "$REF" | tr ',' '\n' | while IFS= read -r p; do
      case "$p" in ../*) [ -d "$ROOT/$m/$p" ] || echo "$m: $p" ;; esac
    done
  done > /tmp/ugm-missing.$$
  if [ -s /tmp/ugm-missing.$$ ]; then
    MISSING=$(cat /tmp/ugm-missing.$$ | tr '\n' ';')
    rm -f /tmp/ugm-missing.$$
    MSG="引用路径缺失: $MISSING"
    return 1
  fi
  rm -f /tmp/ugm-missing.$$
  MSG="third_party 元数据齐备，五模块 build-native.sh 引用路径全部存在"
  return 0
}

# 同步本体：clone/pull → 拷贝 → 校验
do_sync() {
  if [ -d "$CACHE_DIR/.git" ]; then
    git -C "$CACHE_DIR" pull --ff-only -q || { MSG="官方仓库 pull 失败: $CACHE_DIR"; return 2; }
  else
    git clone --depth 1 -q "$REPO_URL" "$CACHE_DIR" || { MSG="官方仓库 clone 失败（网络？）"; return 2; }
  fi
  mkdir -p "$TP"
  for lib in $LIBS; do
    rm -rf "$TP/$lib"
    cp -r "$CACHE_DIR/metadata/$lib" "$TP/$lib" || { MSG="拷贝 $lib 失败"; return 2; }
  done
  do_check || return 1
  return 0
}

case "${1:-}" in
  --help|-h) usage; exit 0 ;;
  --json)
    if do_check; then printf '{"status":"OK","severity":"info","message":"%s"}\n' "$MSG"; exit 0
    else printf '{"status":"FAIL","severity":"warn","message":"%s"}\n' "$MSG"; exit 1; fi ;;
  check)
    if do_check; then echo "OK: $MSG"; exit 0
    else echo "FAIL: $MSG" >&2; exit 1; fi ;;
  "")
    echo "═══ 同步官方 reachability-metadata（$LIBS）═══"
    if do_sync; then echo "OK: $MSG"; echo "提示：升级依赖版本后记得同步修改各 build-native.sh 的版本路径"; exit 0
    else
      RC=$?
      echo "FAIL: $MSG" >&2
      exit $RC
    fi ;;
  *) usage >&2; exit 2 ;;
esac
