# Data Worker 副本部署手册（多副本扩容）

> 多副本改造（2026-09-13）配套文档。目标形态：主机 1 跑 all-in-one data 镜像
> （collector/worker/ingest，副本恒=1）；主机 2+ 拉取 **worker 变体镜像**跑 N 个
> 副本，与主机 1 竞争消费同一组任务队列（quorum 队列，未 ack 消息自动回归）。

## 1. 传输与网络（已定决策）

- 主机 2 → 主机 1 的 AMQP 走**虚拟网 overlay**（WireGuard/Tailscale 类），AMQP
  明文跑 overlay 内，不叠应用层 TLS。
- 主机 2 的 `.env`：`RABBIT_HOST=<主机1虚拟网地址>`，端口默认 5672。
- 主机 1 侧：compose 的 `lavinmq` 已发布 `0.0.0.0:5672`，无需改动；防火墙只需
  放行 overlay 网段/主机 2 的 overlay IP。
- overlay 闪断（半开 TCP）由应用内心跳 watchdog 兜底（见 §5），无需网络层保活配置。

## 2. 镜像与角色边界（AOT 裁剪矩阵）

| 镜像 | 角色 | 构建期状态 |
|---|---|---|
| `…-service-data` | collector + worker + ingest | all-in-one，角色不可运行期关闭 |
| `…-service-data-worker` | 仅 worker（公告蒸馏 + 向量化） | collector/ingest 已 AOT 物理裁剪 |

**裁剪边界（重要）**：
- worker 变体中 `collector.*` / `ingest.*` 已编译期裁剪，**运行期 env 强开无效**
  （bean 不存在，静默无操作，不报错）。
- all-in-one 中角色也**不可运行期关闭**（AOT 条件构建期求值）。
- worker 变体内置 web-application-type=none：无 Tomcat、无 HTTP 端点、无端口。

## 3. 主机 2 部署步骤

1. 拉镜像：
   ```sh
   podman pull ghcr.io/loveheng/stock-calculator-service-data-worker:<tag>
   ```
2. `.env` 必需键（缺任一 worker 直接 fail-fast 拒绝启动）：
   - `RABBIT_HOST` / `RABBIT_PORT`(5672) / `RABBIT_USER` / `RABBIT_PASS`
   - `CLOUDFLARE_ACCOUNT_ID` / `CLOUDFLARE_API_TOKEN`（embedding 域）
   - `LLM_BASE_URL` / `LLM_API_KEY` / `LLM_MODEL`（announcement 域）
   - 可选：`DATASVC_WORKER_PREFETCH_ANNOUNCEMENTCONCURRENCY`（公告并发消费者，
     默认 1；2~3 可线性提吞吐，注意内存余量）、`DATASVC_HEARTBEAT_*`（心跳参数）
3. 启动（二选一）：
   - compose（复制仓库 docker-compose.yml + .env）：
     ```sh
     docker compose --profile data-worker up -d data-worker
     ```
   - 裸容器：
     ```sh
     podman run -d --name data-worker-1 --env-file .env \
       --memory 2g --restart unless-stopped \
       ghcr.io/loveheng/stock-calculator-service-data-worker:<tag>
     ```
4. **每副本不同 key**：key 是运行期值（非 AOT 条件），各副本 env 各配各的即可；
   同机多副本不同 key 用多个 env 文件（`podman run --env-file worker-2.env`）或
   复制 compose service 块改名。`EmbeddingRateLimiter` 每实例生效，不同 key 天然
   独立限速（200 req/min/key）。

## 4. 多副本语义（评审确认项）

- **任务载荷自包含**：公告任务只含 announcementId/adjunctUrl/title，worker 自行
  下载 PDF（进程内 pdfCache 按 URL 去重），临时文件不出消息生命周期——无共享
  文件系统依赖，多机部署无需任何共享存储。
- **结果摄取幂等**：主服务侧 FAILED 终态不回退、非 PENDING 迟到回报忽略、DONE
  判重 upsert。重投递导致的重复 LLM 开销是 D6 at-least-once 的既有语义窗口
  （单副本同样存在），多副本不破坏正确性；如需收紧可后续加发布端 in-flight 标记。
- **history-sync 只由 all-in-one 消费**：HistorySyncWorker 挂 collector 门控，
  worker 副本不参与——对 CLS 的礼貌限速单消费者语义，刻意为之。
- **保守行为**：主服务 D8 额度记账为单 CF 账号口径（多账号下不会超发、只是偏保守）；
  发布端 RATE_LIMITED 熔断全局生效（任一 key 打出 429 即全局限速，偏保守不打爆）。

## 5. 存活探针两层分工

| 层 | 机制 | 作用 |
|---|---|---|
| 应用层 | `MqHeartbeatWatchdog`（无条件装配）：独立线程池每 60s 对已知队列 passive declare 往返（10s 超时）；连续 3 次失败 → log.error + System.exit | **自愈**：覆盖死锁/假死/半开 TCP/消费静默饿死；进程退出触发 `restart: unless-stopped` |
| 镜像层 | `Dockerfile.worker` HEALTHCHECK：心跳文件 `/tmp/datasvc-heartbeat` mtime 新鲜度（>180s 不健康） | **可见性**：`podman ps` 显示 healthy/unhealthy（plain compose 的 unhealthy 不自动重启容器） |

参数经 `datasvc.heartbeat.*`（interval-ms / timeout-ms / failure-threshold / file）
调整；JVM 本地开发可 `DATASVC_HEARTBEAT_ENABLED=false` 关闭。

## 6. 验收清单

- [ ] LavinMQ 管理台（主机 1:15672）`task.announcement.process.q` 与
      `task.embedding.compute.q` 消费者数 = 副本数 + 1（all-in-one）
- [ ] 两台机器日志各见本域任务消费（`announcement processed` / embedding 行）
- [ ] `podman ps`：data-worker 容器 health 显示 healthy（启动 90s 后）
- [ ] 停掉任一副本：在途消息回归队列由其余副本完成（quorum 队列语义）

## 7. 扩容收益天花板（先看再买机器）

1. **先榨现有硬件**：`DATASVC_WORKER_PREFETCH_ANNOUNCEMENTCONCURRENCY=2~3` 在
   主机 1 现有 all-in-one 上即可生效（值可运行期 env 覆盖，无需换镜像）。
2. **LLM 单 key RPM 是公告蒸馏的天花板**：多副本共享同一 provider 配额时收益
   封顶，按 key 分配副本才是真扩容。
3. **embedding 日额度 60000 条/天由主服务发布端集中记账（D8）**：副本再多日
   消化速度封顶不变，embedding 队列积压加机器无用。
