---
dev-loop: lessons
format: v1
epic: global
total-merged: 0
last-merge: none
---

# stock-calculator-service 项目经验总结

（正文＝按模块归类的避坑规则 SSOT，由 /merge 从底部追加区归纳提升；正文行禁用 `- [` 开头，该前缀专属追加区）

## 追加区

- [terminal沙箱] 静默写命令（cat >> / sed -i / mkdir）报 exit 2 "Cannot set tty process group (No such process)" ➔ 沙箱 pty 退出伪故障，命令本体实际已执行成功 ➔ 勿据 exit code 盲目重试（会重复追加/重复写入），先读目标文件核验落盘结果再决定动作 (Ref: stock-common)
