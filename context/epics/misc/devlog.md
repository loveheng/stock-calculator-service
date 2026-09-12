---
dev-loop: devlog
format: v1
epic: misc
total-merged: 0
last-merge: none
---

# misc 开发过程日志

（散修/小改动按行追加；≥5 条自动归并进 memory.md）

- [2026-09-12] [变更]: dev-loop skill 升级 V3.2（新增 misc 常驻杂项挂靠协议：默认绑定、转正出口、audit/done 护栏），本项目同步落盘 misc 骨架
- [2026-09-12] [变更]: CI 按模块条件构建：docker-image.yml 重构为 changes 检测 + build-main/build-data 双 job（dorny/paths-filter 路径过滤，contract/根POM/mvnw 变更双端重建，tag/手动强制全量），新增 data 模块 Dockerfile.native（镜像名 ghcr.io/<repo>-data，8080/ingest），修复 main build-native.sh 拆分后缺失的父POM+contract install（CI 冷缓存会挂）
- [2026-09-12] [变更]: dev-loop skill 升级 V3.3（补 3 处口径：CURRENT 缺失/失配处置、归并与 /status 计数对象含 misc、/file 裸调用）
