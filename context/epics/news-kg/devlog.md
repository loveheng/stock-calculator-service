---
dev-loop: devlog
format: v1
epic: news-kg
total-merged: 1
last-merge: 2026-09-19
---

# news-kg 开发过程日志

（散修/小改动按行追加；≥5 条自动归并进 memory.md）

## 追加区
- [2026-09-20] [变更]: data 服务 native openai 反射崩溃修复——移植 main 生成器轮 17/18 为 stock-calculator-data/gen-openai-metadata.py（4,719 any-setter 类 + com.openai.core.** 200 类/1,074 方法），build-native.sh 增步骤 2.5 产物守卫；发现并绕过本地 AOT 产物陈旧陷阱（--no-pkg 二进制缺 kg worker，全量构建重生）；一次性 broker + mock LLM E2E 全绿（未建模字段响应零反射崩溃、PERMANENT 失败分类正常）
