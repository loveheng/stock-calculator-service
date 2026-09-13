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
- [测试环境] 宿主机跑 mvnw 测试报 UnknownHostException: lavinmq / Unable to determine Dialect without JDBC metadata ➔ .env 的 POSTGRES_URL/RABBIT_HOST 写的是 docker 网络内部主机名，宿主机解析不了 ➔ 宿主机测试须覆盖 SPRING_PROFILES_ACTIVE=postgres + POSTGRES_URL=localhost:5432 + RABBIT_HOST/REDIS_HOST=localhost（Ref: misc）
- [测试环境] data 套件 ResultPublisherTopologyTest 断言 result.ingest.q 深度大于等于 1 失败（实际=0）➔ 本地 live 的 app 容器正消费该队列，测试消息被秒吃 ➔ 跑 data 集成套件前先停 app 容器，跑完恢复（Ref: misc）
- [构建] 全量验证命令 -Dtest=!TaskServiceTest 在 contract 空测试模块报 No tests matching pattern ➔ surefire 3.5.6 对排除模式同样按指定测试处理，-DfailIfNoTests 不覆盖该场景 ➔ 补 -Dsurefire.failIfNoSpecifiedTests=false（Ref: misc）
