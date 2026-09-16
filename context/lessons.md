---
dev-loop: lessons
format: v1
epic: global
total-merged: 2
last-merge: 2026-09-15
---

# stock-calculator-service 项目经验总结

（正文＝按模块归类的避坑规则 SSOT，由 /merge 从底部追加区归纳提升；正文行禁用 `- [` 开头，该前缀专属追加区）

## terminal/沙箱

terminal 沙箱下静默写命令（cat >> / sed -i / mkdir）报 exit 2 "Cannot set tty process group" 属 pty 退出伪故障，命令本体已执行成功；勿据 exit code 盲目重试（会重复写入），先读目标文件核验落盘结果再决定动作。(Ref: stock-common)

## 测试环境

宿主机跑 mvnw 测试：.env 的 POSTGRES_URL/RABBIT_HOST 是 docker 网络内部主机名，宿主机解析不了（UnknownHostException: lavinmq / Unable to determine Dialect）→ 须覆盖 SPRING_PROFILES_ACTIVE=postgres + POSTGRES_URL=localhost:5432 + RABBIT_HOST/REDIS_HOST=localhost。(Ref: misc)

跑 data 集成套件（ResultPublisherTopologyTest 断言 result.ingest.q 深度）前先停本地 live 的 app 容器——测试消息会被秒吃导致断言失败；跑完恢复。(Ref: misc)

全量验证 -Dtest=!TaskServiceTest 在 contract 空测试模块报 No tests matching pattern（surefire 3.5.6 对排除模式同样按指定测试处理）→ 补 -Dsurefire.failIfNoSpecifiedTests=false 与 -DfailIfNoTests。(Ref: misc)

## native（GraalVM）

native 模块注解属性禁止运行期 SpEL 引 bean 属性：@RabbitListener 队列名写 `#{bean.name}` JVM 可跑、native 启动即 "Expression parsing failed"（SpEL 属性访问无反射元数据，Queue.getName() 不在可达性集合）。匿名队列用 @QueueBinding 声明式：@Queue(value="", durable="false", exclusive="true", autoDelete="true")（内部即 AnonymousQueue），一切注解属性用常量。(Ref: misc)

## 检索/引用核查

grep 工具全仓引用核查时 include_pattern 写 'docs/**' 全部落空，断言「零断链」实为假阴性（docs 重组断链漏检）→ glob 以含项目根的全路径为锚（如 'stock-calculator-service/docs/**'）；全仓核查免 include_pattern、用精确文件名模式逐项验证（规范条目另见 stock-calculator-docs §五）。(Ref: misc)

## 追加区
