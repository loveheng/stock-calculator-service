---
dev-loop: devlog
format: v1
epic: copilot-memory
total-merged: 1
last-merge: 2026-09-17
---

# copilot-memory 开发过程日志

（散修/小改动按行追加；≥5 条自动归并进 memory.md）

## 追加区
- [2026-09-17] [验证]: 用户确认后删除 live LavinMQ 存量 task.history.sync.q（参数与当前代码声明冲突 406，测试环境），data 套件复跑 79 run 全绿；broker 实测确认：3 个 memory 队列+绑定按新参数落地（delay.q classic + DLX→stockcalc.results + tick key），task.history.sync.q 重建带 single-active-consumer
- [2026-09-17] [修复]: 冒烟实测揪出 LLM 输出截断——UnexpectedEndOfInput(expected close marker for Array through ["memories"])两连挂：LlmGateway 请求体未显式传 max_tokens,走 CF 网关隐式缺省(偏小),输出长即腰斩在数组中间(此前 4 次成功系输出短);修复=LlmGatewayProperties.maxTokens(默认 4096,仅放宽上限不改自然停止)+ 两 worker 解析失败随日志输出 rawTail(200 字符尾段,截断问题看尾段定位)；用户重启 data + 补聊一轮后对账通过：水位 8→20、锁清、新条目「关注领域」落库、画像未触发属正确(ΔCount 未达阈值滚存)
- [2026-09-17] [验证]: 冒烟环境观察记录——用户 IDE 起 main/data(调试模式),data 需 DATASVC_WORKER_ENABLED=true 才挂 memory worker(缺省 off 队列积压 0 消费者);IDE JVM 代理参数(proxyHost)曾致 LLM 调用失败、去掉后直连正常;IDEX 控制台日志不可文件化,观察改走 MQ 管理 API + psql 容器(pycopg 环境无,用 docker exec psql)
