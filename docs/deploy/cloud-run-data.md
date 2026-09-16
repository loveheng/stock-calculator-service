---
status: active
updated: 2026-09-13
---

# Cloud Run data 副本部署手册（公网 AMQP 直连）

> 2026-09-13。`docs/deploy/data-worker-replica.md` §3 Cloud Run 条目的展开版。
> 镜像：`ghcr.io/loveheng/stock-calculator-service-data:8ac8b1f`（GHCR 包为
> public，Cloud Run 直接拉取，无需中转，见 §3）。
> 网络决策：Cloud Run 无法加入 WireGuard/Tailscale overlay（容器环境不能起 tun
> 网卡，userspace SOCKS 代理对 RabbitMQ Java 客户端不可靠），故走**公网直连**：
> 明文 AMQP 5672，不配 TLS 证书（MQ 数据均为公开渠道信息与蒸馏产物，非敏感，
> 2026-09-13 定案），路由器/防火墙放行，Cloud Run 经公网 IP 直连。
> 实际拓扑（2026-09-13 落地）：broker 在云服务器 35.208.158.36（其上已部署
> main + data 并暴露 5672），Cloud Run 副本直连该机；本手册所称「主机 1」
> 泛指 broker 所在机。首次部署验收记录见 §6 末。

## 0. 拓扑与硬性约束

| 项 | 值 | 说明 |
|---|---|---|
| 镜像 | `…-service-data:<tag>` all-in-one | collector/worker/ingest 三角色 AOT 期钉死，副本等价 |
| 实例形态 | `--no-cpu-throttling` + `--min-instances 1` | **硬要求**：AMQP 消费者是后台长任务，不能缩零/请求期外断粮 |
| 副本数 | `--max-instances 1` 起步 | 「恰一个」语义由 MQ 仲裁，多副本安全但按需加（replica 手册 §7） |
| 自愈 | `MqHeartbeatWatchdog` 连续 3 次失败 → System.exit | Cloud Run 自动重建实例，与裸容器 restart 策略等价 |
| 配置 | 全部来自 env / Secret Manager | 值与项目根 .env 同源（副本与主机 1 共用一套凭据） |
| 成本 | 常驻 1 vCPU + 1 GiB ≈ 65–90 USD/月 | no-cpu-throttling 下 1 vCPU 即下限；区域浮动（asia-east1 偏上限） |

## 1. 主机 1：公网暴露 AMQP 5672（一次性）

决策：MQ 数据均为公开渠道信息（公告 PDF、CLS 电文）与蒸馏产物，非敏感，走
明文 AMQP 直连，不配 TLS。compose 的 lavinmq 已发布 `0.0.0.0:5672`（middleware
层现状），**主机 1 侧零改动**。

⚠️ 执行前确认 `RABBIT_PASS` 为长随机串——5672 公网可达后防线就剩口令：被爆破
成功 = 队列可读/写/删（lavinmq-init 授的是全权限账号）；且 AMQP PLAIN 认证
不带 TLS 时凭据在握手中明文传输，可被嗅探。

### 1.1 公网暴露

- 路由器/防火墙转发 **5672/tcp** 到主机 1（仅此端口，15672 管理台勿暴露公网）；
  主机 1 本机防火墙（ufw/firewalld）同步放行。
- 外网验证（任一非主机 1 网络的机器）：
  ```sh
  nc -vz <公网IP> 5672
  # succeeded 即端口可达；完整 AMQP 握手随 §6 部署后验收
  ```
- 动态 IP：变动后重跑 deploy 脚本改 RABBIT_HOST（约 1 分钟，窗口期由 watchdog
  自愈兜底）；静态 IP 一次配好不动。

## 2. GCP 项目初始化（一次性）

本机未装 gcloud 时先安装 Google Cloud CLI，然后：

```sh
gcloud auth login
gcloud config set project <PROJECT_ID>

gcloud services enable run.googleapis.com secretmanager.googleapis.com
```

运行时服务账号（scs-data-run）与各 Secret 的授权由 deploy 脚本自动补建，无需手工。

## 3. 镜像拉取（GHCR 包 public，无需中转）

`loveheng/stock-calculator-service-data` 为 public 包（匿名 token 可读
tags/manifest，`8ac8b1f` 匿名 200），Cloud Run 直接引用 ghcr.io 地址，
本机 `docker pull` 亦无需登录：

```sh
docker pull ghcr.io/loveheng/stock-calculator-service-data:8ac8b1f
```

仅当包将来改回 private 时才需要处理，两选一：

- **中转 Artifact Registry**：docker/podman pull → tag 为
  `asia-east1-docker.pkg.dev/<PROJECT_ID>/scs/stock-calculator-service-data:<tag>`
  → push（需 `gcloud services enable artifactregistry.googleapis.com` + 建仓 +
  `gcloud auth configure-docker`），deploy 脚本 IMAGE 换成 AR 地址。
- **AR 远程仓库 pull-through**：凭据文件
  `{"_type":"basic","username":"<GH用户名>","password":"<PAT>"}`（classic PAT，
  勾 `read:packages`）：
  ```sh
  gcloud artifacts repositories create scs-remote \
    --repository-format=docker --location=asia-east1 \
    --mode=remote-repository \
    --remote-repository-config-type=upstream-registry \
    --remote-docker-repo=ghcr.io \
    --upstream-credentials-file=creds.json
  ```
  镜像完整路径以首次拉取后 `gcloud artifacts docker images list` 输出为准。

## 4. Secrets（脚本自动同步）

值来源 = 项目根 `.env`（副本与主机 1 共用同一套凭据），deploy 脚本每次运行自动
对齐 Secret Manager，无需手工：

- secret 缺失 → 创建并授予 scs-data-run 读取（secretAccessor）
- `.env` 值有变化 → 加新版本（`:latest` 随即生效）
- 值无变化 → 跳过

映射：`scs-data-rabbit-pass`←RABBIT_PASS、`scs-data-cf-api-token`←CLOUDFLARE_API_TOKEN、
`scs-data-llm-api-key`←LLM_API_KEY、`scs-data-ingest-secret`←INGEST_SECRET。
`INGEST_SECRET` 在 .env 缺失时：有注释掉的旧值则恢复启用（沿用旧值），否则生成
并追加回 .env（webhook 发送方须用同一值）。

### 4.1 环境变量总表（deploy 脚本已内置）

| env | 来源 | 形态 |
|---|---|---|
| RABBIT_HOST | 主机 1 公网 IP | --set-env-vars |
| RABBIT_PORT=5672 | 固定 | --set-env-vars |
| RABBIT_USER / LLM_BASE_URL / LLM_MODEL / CLOUDFLARE_ACCOUNT_ID | .env 同值 | --set-env-vars |
| RABBIT_PASS / CLOUDFLARE_API_TOKEN / LLM_API_KEY / INGEST_SECRET | .env 同值 | --set-secrets |

worker/collector/ingest 三角色开关**不用配**：AOT 构建期已钉死全开。

## 5. 部署（可重复）

```sh
bash scripts/agent-tools/deploy-cloud-run.sh [tag]    # 项目池；唯一必填 PROJECT_ID，凭据自动取 .env，
                                  # tag 缺省 8ac8b1f
```

等价的手工命令（脚本内容展开，方便单独调整）：

```sh
gcloud run deploy scs-data \
  --project <PROJECT_ID> --region asia-east1 \
  --image ghcr.io/loveheng/stock-calculator-service-data:8ac8b1f \
  --service-account scs-data-run@<PROJECT_ID>.iam.gserviceaccount.com \
  --cpu 1 --memory 1Gi \
  --no-cpu-throttling \
  --min-instances 1 --max-instances 1 \
  --port 8080 \
  --allow-unauthenticated \
  --set-env-vars "RABBIT_HOST=<公网IP>,RABBIT_PORT=5672,RABBIT_USER=scs,CLOUDFLARE_ACCOUNT_ID=<ID>,LLM_BASE_URL=<URL>,LLM_MODEL=<MODEL>" \
  --set-secrets "RABBIT_PASS=scs-data-rabbit-pass:latest,CLOUDFLARE_API_TOKEN=scs-data-cf-api-token:latest,LLM_API_KEY=scs-data-llm-api-key:latest,INGEST_SECRET=scs-data-ingest-secret:latest"
```

- `--allow-unauthenticated`：ingest webhook 需公网可达，鉴权在应用层
  HMAC-SHA256（INGEST_SECRET），不放 Cloud Run IAM 前置。
- `--port 8080`：native 进程 Spring Boot 默认端口，与 Dockerfile EXPOSE 一致。
- 部署完成输出的 `status.url` 即 ingest webhook 收口地址。

## 6. 验收清单（对齐 docs/deploy/data-worker-replica.md §6）

- [ ] 实例稳定运行 10 分钟无重建：
      `gcloud run services describe scs-data --region asia-east1 --format yaml` 看 status.conditions
- [ ] 日志见 MQ 消费者注册、无 fail-fast / watchdog exit：
      `gcloud run services logs read scs-data --region asia-east1 --limit=100`
- [ ] LavinMQ 管理台（主机 1:15672）：`task.announcement.process.q` 与
      `task.embedding.compute.q` 消费者数 +1；`task.history.sync` 消费者 +1
      （多数时间 Waiting，SAC 归主机 1 副本持有）
- [ ] webhook 探活：
      `curl -i -X POST https://<status.url>/api/ingest/cls` → 400/401
      （端口活、HMAC 在守），而非 404/503；合法签名请求返回 202
- [ ] 停掉云副本不影响其余副本（竞争消费/SAC failover 语义）
- [ ] 队列清零无死信

### 首次部署验收记录（2026-09-13）

- 部署 5 分钟内实例即开始消化 embedding 队列积压（`embedding computed, refId=…,
  dims=1024` 连续出账），300 行日志 ERROR/WARN = 0；
- `POST /api/ingest/cls` → 400（应用层拒绝）、`GET /` → 404，端口活、HMAC 在守；
- broker 侧 `task.announcement.process.q` / `task.embedding.compute.q` /
  `task.history.sync.q` 各 +1 消费者，messages/unacked 全部归零。

## 7. 运维

- **发新版**：`bash scripts/agent-tools/deploy-cloud-run.sh <新tag>` 即可（镜像 public 直接拉取；
  包若改回 private，先按 §3 中转并改脚本 IMAGE 为 AR 地址）。
- **回滚**：重跑脚本换回旧 tag，或 Cloud Run 控制台 revision 一键回滚。
- **扩副本**：改 `--max-instances`（Cloud Run 不感知 MQ 积压，靠这里手工定上限）；
  吞吐优先 `DATASVC_WORKER_PREFETCH_ANNOUNCEMENTCONCURRENCY=2~3` 加进 env，
  见 replica 手册 §7 收益天花板。
- **v2.5 前置**：舰队须已跑 v2.5+ 镜像（`task.history.sync` SAC 拓扑）。主机 1
  当前 DATA_IMAGE_TAG 默认 3a4f486 即 v2.5 产物，无需 §4 队列迁移。

### 故障速查

| 症状 | 定位 |
|---|---|
| 部署失败 image pull denied | 镜像 tag 不存在；包改回 private 时按 §3 中转 |
| 启动秒退（fail-fast 日志） | 缺 env/secret，对照 §4.1 总表 |
| 反复重建：日志连 3 条 watchdog error 后 exit | MQ 通路断了：防火墙/端口转发（§1.1 重验） |
| ingest 401 | INGEST_SECRET 与发方不一致（应同 .env 同值） |
| 日志 access refused（认证失败） | RABBIT_USER/RABBIT_PASS 与主机 1 .env 不一致 |
