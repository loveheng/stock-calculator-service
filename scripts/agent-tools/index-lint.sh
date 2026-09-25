#!/bin/sh
# index-lint.sh — 归属表 ↔ 代码目录 双向校验（project-index「双向 guardrail」的后端实现）
# 反向：Base 包顶层领域目录（main/data/contract）→ 必须在索引 SKILL.md 登记（防孤儿域/漏项）
# 正向：归属表 find 展开命令中的路径 → 必须在磁盘存在（防断链/域改名后失联）
#
# toolbox-script
# format: v1
# name: index-lint
# summary: 归属表与代码目录双向校验（project-index guardrail）
# trigger: manual
# cat: docs
# alias: il
# platform: unix

cd "$(dirname "$0")/../.." || exit 2

usage() {
  cat <<'EOF'
用法: sh scripts/agent-tools/index-lint.sh [--json|--help]
  裸跑    双向校验并打印明细，退出码非 0 = 有问题
  --json  同一校验，仅输出一行 JSON 结论
  --help  本帮助
EOF
}

main() {
  IDX=.agents/skills/stock-calculator-service-index/SKILL.md
  BASE=stock-calculator-main/src/main/java/com/zzh/stock_calculator
  DATA=stock-calculator-data/src/main/java/com/zzh/stock_calculator/data
  CONTRACT=stock-calculator-contract/src/main/java/com/zzh/stockcalc/contract

  [ -f "$IDX" ] || { echo "索引不存在: $IDX"; return 1; }

  fail=0

  # 1) 反向：领域目录必须已登记（按名字在索引中出现即算，宽松匹配防误报）
  for d in "$BASE"/*/ "$DATA"/*/ "$CONTRACT"/*/; do
    [ -d "$d" ] || continue
    name=$(basename "$d")
    if ! grep -q "$name" "$IDX"; then
      echo "索引缺登记: $name（$d）"
      fail=1
    fi
  done

  # 2) 正向：归属表展开命令中的路径必须存在
  for p in $(grep -o 'find [a-zA-Z0-9/_.-]*' "$IDX" | sed 's/^find //'); do
    if [ ! -d "$p" ]; then
      echo "索引路径不存在: $p"
      fail=1
    fi
  done

  # 3) 防膨胀预算（提示级，不拦截）
  lines=$(wc -l < "$IDX")
  if [ "$lines" -gt 90 ]; then
    echo "提示: 索引正文 $lines 行（软上限 ~80 行）——优先扩收集脚本，不扩表"
  fi

  return $fail
}

case "${1-}" in
  --help|-h)
    usage
    ;;
  --json)
    if main >/dev/null 2>&1; then
      printf '{"status":"OK","severity":"info","message":"索引归属表与代码目录双向校验通过"}\n'
      exit 0
    fi
    printf '{"status":"FAIL","severity":"warn","message":"索引校验未通过，裸跑查看明细"}\n'
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
