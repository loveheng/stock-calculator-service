#!/bin/sh
# docs-index-lint.sh — docs 收集视图 + README 覆盖双向校验（stock-calculator-docs §八 的补充）
# 设计原则（project-index）：可推导的不维护——文档清单/状态/updated 全部现场 derive，不建第二份拷贝。
#
# toolbox-script
# format: v1
# name: docs-index-lint
# summary: docs 收集视图与 README 覆盖双向校验（docs skill §八 补充）
# trigger: manual
# alias: dl
# platform: unix

cd "$(dirname "$0")/../.." || exit 2

usage() {
  cat <<'EOF'
用法: sh scripts/agent-tools/docs-index-lint.sh [--json|-c|--help]
  裸跑    覆盖校验 + 收集视图，退出码非 0 = 有问题
  -c      仅输出收集视图（路径 status updated）
  --json  同一校验，仅输出一行 JSON 结论
  --help  本帮助
EOF
}

collect() {
  for f in docs/*/*.md docs/README.md; do
    [ -f "$f" ] || continue
    s=$(sed -n '2s/^status: //p' "$f")
    u=$(sed -n '3s/^updated: //p' "$f")
    echo "$f  status=$s  updated=$u"
  done
}

lint_check() {
  fail=0
  # 1) README 链接指向的文件必须存在
  for p in $(grep -o ']([a-zA-Z0-9./_-]*\.md)' docs/README.md | sed 's/^](//; s/)$//'); do
    case "$p" in docs/*) p=${p#docs/} ;; esac
    if [ ! -f "docs/$p" ]; then
      echo "README 引用不存在: docs/$p"
      fail=1
    fi
  done
  # 2) 每个非 README 文档必须在 README 有条目（域墓碑 README 天然跳过，符合 §四 零操作）
  for f in docs/*/*.md; do
    [ -f "$f" ] || continue
    case "$f" in */README.md) continue ;; esac
    rel=${f#docs/}
    if ! grep -q "($rel)" docs/README.md; then
      echo "README 缺条目: docs/$rel"
      fail=1
    fi
  done
  return $fail
}

main() {
  fail=0
  lint_check || fail=1
  # 收集视图（配合 §八 的 frontmatter 四项检查：status= 为空即 frontmatter 异常）
  echo "---- 收集视图 ----"
  collect
  return $fail
}

case "${1-}" in
  --help|-h)
    usage
    ;;
  -c)
    collect
    ;;
  --json)
    if main >/dev/null 2>&1; then
      printf '{"status":"OK","severity":"info","message":"docs README 覆盖双向校验通过"}\n'
      exit 0
    fi
    printf '{"status":"FAIL","severity":"warn","message":"docs 索引校验未通过，裸跑查看明细"}\n'
    exit 1
    ;;
  "")
    main
    ;;
  *)
    usage
    exit 2
    ;;
esac
