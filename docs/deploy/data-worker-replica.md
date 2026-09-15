---
status: active
updated: 2026-09-13
---

# Data 副本部署手册（单镜像任意副本）

> 单镜像多副本改造（2026-09-13，v2.5）配套文档。目标形态：全舰队只有一个镜像
> `…-service-data`（collector/worker/ingest 三角色全开），副本数不设限、位置不限
> （主机 1、其他主机、Cloud Run 均可），按需增减——所有「恰好一个」语义由 MQ
> 协议仲裁，打包不再承载角色区分（worker 变体已退役）。

## 1. 传输与网络（沿用已定决策）

- 副本机 → 主机 1 的 AMQP 走**虚拟网 overlay**（WireGuard/Tailscale 类），AMQP
  明文跑 overlay 内，不叠应用层 TLS。
- 副本机的 `.env`：`RABBIT_HOST=<主机1虚拟网地址>`，端口默认 5672。
- 主机 1 侧：compose 的 `lavinmq` 已发布 `0.0.0.0:5672`，无需改动；防火墙只需
  放行 overlay 网段/副本机的 overlay IP。
- overlay 闪断（半开 TCP）由应用内心跳 watchdog 兜底（见 §5），无需网络层保活配置。

## 2. 镜像与语义边界

| 项 | 状态 |
|---|---|
| 镜像 | `…-service-data` 唯一，三角色全开（AOT 条件构建期求值，不可运行期关闭） |
| 常态拉取单飞 | 自循环种子 + 续种深度守卫：全局仅一条种子链，每轮拉取由任一副本竞争消费 |
| 历史补录恰一个 | `task.history.sync` 挂 `x-single-active-consumer`：抢到就是谁的，活跃副本挂掉自动顶替 |
| worker 扩容 | `task.announcement.process.q` / `task.embedding.compute.q` 竞争消费（quorum 队列，未 ack 自动回归） |
| 控制面配置 | 每副本独占匿名队列（exclusive + auto-delete）绑 `control.#` 广播，无配置漂移 |

**副本间差异只在 env**：`RABBIT_HOST` 指向主机 1；`CLOUDFLARE_*` / `LLM_*`
每副本各配各自 key（运行期值，`EmbeddingRateLimiter` 每实例生效，不同 key 天然
独立限速）。

## 3. 副本机部署步骤

1. 拉镜像：
   ```sh
   podman pull ghcr.io/loveheng/stock-calculator-service-data:<tag>
   ```
2. `.env` 必需键（缺任一 worker 直接 fail-fast 拒绝启动）：
   - `RABBIT_HOST` / `RABBIT_PORT`(5672) / `RABBIT_USER` / `RABBIT_PASS`
   - `CLOUDFLARE_ACCOUNT_ID` / `CLOUDFLARE_API_TOKEN`（embedding 域）
   - `LLM_BASE_URL` / `LLM_API_KEY` / `LLM_MODEL`（announcement 域）
   - `INGEST_SECRET`（ingest webhook；纯 worker 副本可不管）
   - 可选：`DATASVC_WORKER_PREFETCH_ANNOUNCEMENTCONCURRENCY`（公告并发消费者，
     默认 1；2~3 可线性提吞吐，注意内存余量）、`DATASVC_HEARTBEAT_*`（心跳参数）
3. 启动（三选一）：
   - compose（复制仓库 docker-compose.app.yml + .env 到副本机，副本机不跑中间件，
     先 `docker network create scs-net`，RABBIT_HOST 指向主机 1 地址）：
     ```sh
     docker compose -f docker-compose.app.yml up -d data
     ```
   - 裸容器：
     ```sh
     podman run -d --name data-replica-1 --env-file .env \
       --restart unless-stopped \
       ghcr.io/loveheng/stock-calculator-service-data:<tag>
     ```
   - Cloud Run（ingest webhook 收口首选，公网 HTTPS + INGEST_SECRET 鉴权）：
     ```sh
     gcloud run deploy scs-data \
       --image ghcr.io/loveheng/stock-calculator-service-data:<tag> \
       --cpu 1 --memory 1Gi \
       --no-cpu-throttling \
       --min-instances 1 --max-instances <按需> \
       --set-env-vars RABBIT_HOST=...,RABBIT_USER=...,RABBIT_PASS=...
     ```
     注意：`--no-cpu-throttling` + `min-instances>=1` 是硬要求（AMQP 消费者是
     后台长任务，不能缩零/请求期外断粮）；Cloud Run 不感知 MQ 积压，扩容上限
     靠 max-instances 手工定。Cloud Run 无法加入 overlay，AMQP 走公网明文
     5672 直连（数据非敏感不配 TLS），完整步骤（公网暴露/GHCR 直接拉取/
     Secret Manager/验收/故障速查）见 docs/deploy/cloud-run-data.md。
4. 同宿主机多副本：`data` service 的 `18081:8080` 端口映射会冲突——加副本时
   去掉映射（仅 ingest webhook 需要；webhook 收口副本保留映射即可）。

## 4. 一次性迁移操作（v2.5 拓扑变更）

- `task.history.sync` 新增 `x-single-active-consumer` 参数：**新镜像启动前先在
  LavinMQ 管理台删除旧队列**（补录为运维触发动作，空队列零数据迁移；不删则
  声明参数不匹配 PRECONDITION_FAILED，data 启动失败）。
- `collector.control.q` 被每副本匿名队列取代：割接完成后在管理台删除旧队列
  （残留无害，仅占位）。

## 5. 存活探针两层分工

| 层 | 机制 | 作用 |
|---|---|---|
| 应用层 | `MqHeartbeatWatchdog`（无条件装配）：独立线程池每 60s 对已知队列 passive declare 往返（10s 超时）；连续 3 次失败 → log.error + System.exit | **自愈**：覆盖死锁/假死/半开 TCP/消费静默饿死；进程退出触发 `restart: unless-stopped` / Cloud Run 实例重建 |
| 镜像层 | `Dockerfile.native` HEALTHCHECK：ingest HTTP 端点探活（curl） | **可见性**：`podman ps` / Cloud Run 显示 healthy/unhealthy（plain compose 的 unhealthy 不自动重启容器） |

参数经 `datasvc.heartbeat.*`（interval-ms / timeout-ms / failure-threshold / file）
调整；JVM 本地开发可 `DATASVC_HEARTBEAT_ENABLED=false` 关闭。

## 6. 验收清单

- [ ] LavinMQ 管理台（主机 1:15672）`task.announcement.process.q` 与
      `task.embedding.compute.q` 消费者数 = 副本数 × 各自 prefetch 配置
- [ ] `task.history.sync` 消费者数 = 副本数，其中恰一个 active（其余 Waiting）
- [ ] 各副本日志见本域任务消费（`announcement processed` / embedding 行）
- [ ] `podman ps`：data 容器 health 显示 healthy
- [ ] 停掉任一副本：在途消息回归队列由其余副本完成（quorum 队列语义）；
      补录活跃副本挂掉后其余副本自动接管（SAC failover）

## 7. 扩容收益天花板（先看再买机器）

1. **先榨现有硬件**：`DATASVC_WORKER_PREFETCH_ANNOUNCEMENTCONCURRENCY=2~3` 在
   任一副本上即可生效（值可运行期 env 覆盖，无需换镜像）。
2. **LLM 单 key RPM 是公告蒸馏的天花板**：多副本共享同一 provider 配额时收益
   封顶，按 key 分配副本才是真扩容。
3. **embedding 日额度 60000 条/天由主服务发布端集中记账（D8）**：副本再多日
   消化速度封顶不变，embedding 队列积压加机器无用。
