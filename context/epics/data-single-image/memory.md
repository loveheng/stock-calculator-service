---
dev-loop: memory
format: v1
epic: data-single-image
total-merged: 0
last-merge: none
---

# data-single-image：data 域单镜像多副本改造（设计文档 v2.5）

> 目标：全舰队唯一 data 镜像（-data，collector/worker/ingest 三角色全开），副本数/
> 位置不限、按需增减（主机 1、其他主机、Cloud Run）；「恰好一个」语义全部由 MQ
> 协议仲裁，打包不再承载角色区分。状态：代码与文档完成、本地验证全绿（2026-09-13）。

## 决策与语义（已实施）

- 历史补录：task.history.sync 挂 x-single-active-consumer（quorum+DLX 之上）——抢到
  就是谁的，活跃副本挂掉自动顶替；CLS 动态频控单消费者语义与副本数解耦。
- 控制面：每副本独占匿名队列（@QueueBinding 声明式，exclusive+auto-delete）绑
  stockcalc.control 的 control.# 广播；主服务发布端零改动（交换机与 routing key 不变）。
- 常态拉取：维持种子 + 续种深度守卫不变（L2 复核成立：探-种顺序使多余种子自愈
  收敛，SAC 对拉取环无增益，不加）。
- worker 两域：竞争消费 quorum 队列，天然扩容单位（吞吐天花板 = LLM key RPM 与
  embedding 日额度 D8，见 docs/data-worker-replica-deploy.md §7）。
- worker 变体退役：删 Dockerfile.worker / CI build-data-worker job / build-native.sh
  VARIANT 分支 / compose data-worker 块；每次发布省一次 native 构建。

## 踩坑（详见 lessons.md native 节）

- native 下 @RabbitListener 队列名 SpEL 引 bean（JVM 可跑）启动即挂 Expression
  parsing failed → @QueueBinding 声明式匿名队列（空名 = 独占 auto-delete）修复；
  data 模块注解属性一律声明式常量，禁运行期 SpEL。

## 验收（2026-09-13 全绿）

- data 79 用例全绿；JVM+AOT 上下文与 native 二进制分别对一次性 LavinMQ 实例实测：
  SAC 参数落盘、匿名队列声明/绑定/消费者注册、0.34s 启动零报错；native 13m29s
  重建冒烟双绿（启动 + ingest 503）。
- 文档：docs/data-worker-replica-deploy.md 重写（部署三选一含 Cloud Run 命令 +
  §4 一次性迁移）；data-service-split-design.md v2.5 条目 + §6 部署形态行更新。

## 待办

- 提交推送 dev → 观察 CI build-data 单 job 全链（双 job 并一后的首次运行）
- 部署时执行 docs/data-worker-replica-deploy.md §4 一次性迁移：新镜像启动前删
  task.history.sync 旧队列（本机 dev 栈 data 容器升级同理，否则 PRECONDITION_FAILED），
  割接后清理 collector.control.q
- Cloud Run 部署（可选）：--no-cpu-throttling --min-instances=1（不能缩零），
  RABBIT_HOST 指向主机 1 可达地址，同宿主多副本注意 18081 端口映射冲突

## 断点

- [断点] 下一步：提交推送 dev 触发 CI 验证 build-data 单 job，随后按部署手册 §4 执行队列迁移
