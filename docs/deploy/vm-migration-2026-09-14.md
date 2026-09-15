---
status: active
updated: 2026-09-14
---

# 跨机迁移与部署收口实录（2026-09-14）

> 一次从「消费慢」排查开始、最终完成**三机收敛为双机 + Cloud Run 上下场**的
> 全过程记录。落点：GCP us-central1-a 三台 VM + Cloud Run（前端）+ nginx 统一入口。
> 关联文档：`docs/deploy/data-worker-replica.md`（data 副本手册）、
> `docs/deploy/cloud-run-data.md`（Cloud Run 部署 runbook，Cloud Run 副本已退役）。

## 0. 迁移动因：三个叠加的问题

1. **embedding 回执消费慢到异常**：main 消化 `result.ingest.q` 回执仅
   0.08 条/秒（实测 90 秒只走 7 条），5.9 万条积压按此要 **8.6 天**；worker 侧
   实测净消耗 0.74 条/秒，与早前 Cloud Run 双副本的 ~6 条/秒落差巨大。
2. **1GB 机器塞 5 个服务**：旧 main 机（e2-micro 969MB）同时跑
   app + postgres + redis + lavinmq + data 五个容器，free 仅 65MB、
   swap 用掉 421MB、load 飙到 5.94；此前该机 data 容器「假死」退出
   （watchdog 连续 3 次心跳超时后 System.exit）也是内存挤爆所致。
3. **磁盘随机 IOPS 硬伤**：fio 实测系统盘（pd-standard）随机读
   **94 IOPS**（波动 32–626），慢查询等待事件 10 次采样 9 次卡在
   `IO:DataFileRead` 的 `INSERT INTO vector_store` 上——pgvector 库
   714MB 向量表 + 560MB 文章表的每次写入都在等磁盘冷读。
   （先做过零成本调优：`synchronous_commit=off` +
   `effective_cache_size=256MB`，ALTER DATABASE 级、连接池重连即生效——
   但节奏没变，40 秒/条，证明 fsync 不是主因，IOPS 才是。）

结论：**不是代码问题，是资源结构问题**——单机角色过载 + 机械盘 IOPS，
任何应用层优化都治标不治本，必须重排机器角色。

## 1. 时间线（事件顺序，含走弯路的部分）

### 1.1 排查期（14:00–16:00Z）

- Cloud Run 双副本部署后曾以 ~6 条/秒消化 3.3 万条 embedding 积压；
  后净速掉到 0.74 条/秒 → 定位为 main 对账器持续重发（生产≈消费）+
  两副本共享同一 CF token 触发账户级限速（replica 手册 §7 的天花板预判兑现）。
- 新建 VM `…225458` 跑两个 data 副本（两个 CF 账户分家：337d4609 /
  f0ae42d6 各一），先后撞两堵墙：
  - **rootless podman 网络坏**（pasta 状态损坏：容器 DNS 失效 + 出网
    `Network is unreachable`）→ `podman restart` 容器重置恢复；
  - **镜像版本混用**：Cloud Run 跑 8ac8b1f（`task.history.sync.q` 带
    `x-single-active-consumer`），新 VM 容器跑 3a4f486（无 SAC）→
    `PRECONDITION_FAILED - declared with other arguments` 队列声明互斥。
    教训：**多副本舰队必须统一镜像版本**，SAC 等队列参数改动跨版本不兼容。
- 3.3 万条 embedding 积压曾凭空清零：该队列无 TTL，按速率不可能消费完，
  为管理台手工清空操作（人工确认）。回执队列 5.9 万条**全部是真实计算
  结果**（每条对应一次真实 CF embedding 调用），不可清空重烧配额。

### 1.2 根因期（00:50Z 前后）

上 main 机（35.208.158.36）体检确诊：内存/swap/load 全线超标（见 §0），
回执入库每条 27–61 秒（1024 维向量 + 多表 UPSERT 在换页抖动下被 I/O 拖死）。
期间盘点方案（对照实际规模否决了三个）：

| 方案 | 月成本 | 判定 |
|---|---|---|
| pg 托管服务（Cloud SQL / Supabase / Neon） | $25–40 / 免费档 | 付费比升配 VM 贵一倍；免费档 500MB < 库容 1.5GB，出局 |
| PgBouncer 连接池中间件 | $0 | 治连接数不治 I/O，不对症 |
| 升配现有 VM | ~$13 | 可行，但见 §2 更优解 |

### 1.3 迁移执行（02:00–02:40Z，见 §3）

### 1.4 nginx 收口（02:45–03:30Z，见 §4）

## 2. 目标拓扑与决策依据

**三机 → 双机**（同 VPC us-central1-a，内网互通经 `default-allow-internal`）：

| 机器 | 规格 | 角色 |
|---|---|---|
| `…014126`（34.28.249.164 / 内网 10.128.0.5） | e2-small 2GB + **50GB pd-balanced** | main 全家：app + postgres + redis + lavinmq |
| `…042706`（35.208.158.36 / 内网 10.128.0.2） | e2-micro 1GB + pd-standard 30GB | 统一公网入口（nginx）+ 两个 data 副本 |
| `…225458` | e2-small 2GB | **已删除**（曾短暂跑两个 data 副本） |
| Cloud Run `scs-data` | — | **已删除**（data 副本改走 VM，见 §4.2 取舍） |
| Cloud Run `stock-calculator`（前端） | — | 保留，作为 nginx `/` 的上游 |

决策要点：

- **postgres 不上托管、不迁移出 main 机**（与 09-13 的临时决定一致），
  而是**整台 main 搬到 2GB + pd-balanced 新机**——一次解决内存与 IOPS 两个
  瓶颈，pg 数据目录随 rsync 整体搬迁，无逻辑变更。
- **data 副本回 1GB 旧机**：native 二进制无 DB，两个副本内存占用小
  （首启实测几百 MB），1GB 承载余量足；旧机还承担 nginx 入口（极轻）。
- **中间层结论**：当前规模（单实例 pg、日增几万向量）下，自管 pg 在
  VM 上比托管服务便宜且无网络往返；若未来向量库涨到数 GB、或需要只读
  副本/自动备份，再评估 Cloud SQL（pgvector 已支持）。

## 3. 迁移执行实录（停机窗口约 30 分钟）

### 3.1 前置准备（停机前完成，省窗口时间）

- 新机装 podman/podman-compose/rsync；**预拉全部镜像**（main 462MB +
  中间件三件套）；
- 打通内网 SSH 通道（旧机 admin ↔ 新机 admin 互信，rsync 走内网）；
- compose 与 `.env` 先行 rsync 到新机（`/home/admin/scs/`）。

### 3.2 坑 1：rootless podman 的 uid 映射换算

新旧机的 admin uid 与 subuid 段不同（旧 admin=1001/容器段 165536 起，
新 admin=1000/容器段 100000 起），**数据目录属主直接搬过去容器读不了**。
rsync 后按映射表批量 chown：

```
166534→100998  166535→100999  166536→101000  1001→1000（用户）
1004→1003（组）同组段对应换算
```

pg_wal 少量文件换算后仍显 nouser（chown 顺序竞态），但 postgres 启动
healthy、数据可读，无实际影响。

### 3.3 坑 2：`podman stop` 超时被 SIGKILL

app 容器 30 秒内未退（native 进程信号处理慢）被 SIGKILL——无碍，
MQ 侧由对账器兜底（停机窗口错过的任务全部自动重发）。

### 3.4 停机序列（关键顺序）

```
旧机: podman stop app(30s) → postgres(60s 优雅检查点) → redis → lavinmq
新机: rsync 拉三份数据（1.6G+737M+232K，内网约 2 分钟）
      → 属主换算（§3.2）→ 起中间件 → 验证（vector_store 52107 条无损）
      → 起 app → HTTP 200
```

lavinmq 数据**必须停机拷**：里面有 5.9 万条未消化回执，热拷会损坏队列。

### 3.5 data 副本入驻旧机（含坑 3：compose 项目名撞车）

最初两个 compose 起出同名容器互相覆盖，且默认镜像还是 3a4f486。
修正：`podman-compose -p data1/-p data2` 显式项目名区分
（容器名 `data1_data_1` / `data2_data_1`），镜像统一 **8ac8b1f**，
`RABBIT_HOST=10.128.0.5`（新 main 内网，不再走公网 5672）。

### 3.6 终验（全绿）

- broker 连接 3 条（main + 2 data，全内网）；
- `task.announcement.process.q` / `task.embedding.compute.q` /
  `task.history.sync.q` 消费者各 2（SAC 一活一备），匿名控制队列各 1；
- 停机窗口错过的任务被对账器重发：embedding 队列出现 5.5 万条新积压，
  由双副本按双 CF 账户并行速率消化；
- **回执消化 3.7 条/秒**（旧机 40 秒/条的 **150 倍**），5.9 万条约 4.4
  小时清完；app 日志 442 条/2 分钟，ERROR=0。

### 3.7 清理（全部完成）

删除：data 机 `…225458`（容器先优雅停机，compose/env 备份到跳板机
`/root/scs-backup-20260914/`）、`pgdata-balanced` 20GB 空盘（迁移探索期
的产物，新机根盘本身 pd-balanced，无需此盘）、`allow-postgres` 公网防火墙
规则（5672/5432/15672 全收敛内网）、Cloud Run `scs-data` 服务（早前手删）。
费用净降 ~$13/月（3 台 → 2 台）。

## 4. 公网入口收口（nginx + DNS）

### 4.1 最终链路

```
用户 → Cloudflare DNS: scs.oklhj.eu.org → A 35.208.158.36（data 机，静态入口）
     → data 机 nginx :443（TLS）
         ├─ /            → https://stock-calculator-cnrwr37g2q-uc.a.run.app（Cloud Run 前端）
         ├─ /api/        → http://10.128.0.5:18080（新 main，内网）
         └─ /api/webdav  → 第三方 WebDAV 透传（未动）
```

改动点（`/etc/nginx/conf.d/oklhj.conf`，备份 `.bak-20260914`）：

- `/api/` 上游 `127.0.0.1:18080` → `10.128.0.5:18080`（main 已搬走）；
- `location /` 上游 `127.0.0.1:3000`（本机前端已上云）→ Cloud Run URL，
  补 `proxy_ssl_server_name on` + `Host: <a.run.app>`（SNI 与 Host 必须指
  向 Cloud Run，透传用户域名会被上游拒绝 400——见 §5 教训）；
- Cloudflare 把 `scs` 从 CNAME `ghs.googlehosted.com` 改为 A 记录
  `35.208.158.36`。

### 4.2 为什么 data 副本退出 Cloud Run、前端留在 Cloud Run

- **data 副本**：AMQP 长连接消费者需 `--no-cpu-throttling` + 常驻实例
  （2×1vCPU+1GiB ≈ $130–180/月），且 Cloud Run 不感知 MQ 积压、扩容上限
  靠手调；改走 VM 后成本约为其零头（复用已有 e2-micro），还能与 broker
  同 VPC 内网通信。**结论：后台消费者类负载不适合 Cloud Run，适合常驻 VM。**
- **前端**：无状态、突发流量友好、冷启动可接受——Cloud Run 的甜区，
  继续保留（也省掉 VM 上跑前端的内存）。

### 4.3 切换验证的三个陷阱（排障记录）

1. **getent/本地 DNS 缓存**：权威（1.1.1.1/8.8.8.8）已返回新 A 记录，
   但部分客户端 getent 仍解析到旧 Google 托管地址/IPv6——查解析一律
   以 `nslookup @1.1.1.1` 为准，别信本机缓存；
2. **前端对任何路径回 HTML 200**：用 `/api/` 探测路径拿到的 200 可能
   是前端 SPA fallback（HTML），验证 API 必须看响应体是不是 Spring JSON，
   不能只看状态码；
3. **跳板机 curl `000` 的真因是证书校验失败**（`unknown CA`：自签/链不全
   的源站证书 + curl 严格校验），access log 里家里 IP 的请求实为 200——
   排障时以 **nginx access log 的实际记录**为准，客户端侧失败码先怀疑
   证书/代理再怀疑链路。

## 5. 经验与遗留

### 5.1 沉淀教训（按重要性）

1. **资源诊断三板斧**：`free -h` + load → `podman stats`（容器 RSS）→
   fio IOPS + `pg_stat_activity` 等待事件。本案 10 次采样 9 次
   `IO:DataFileRead` 直接锁定 IOPS，比盲猜快一个数量级。
2. **多副本舰队统一镜像版本**：队列声明参数（SAC/quorum/DLX）跨镜像
   版本不兼容时直接 PRECONDITION_FAILED 互斥，且错在启动期、难察觉。
3. **rootless podman 跨机迁数据目录必须换算 uid/gid 映射**（§3.2 映射表）。
4. **nginx 同块内后写的 `proxy_set_header Host` 覆盖先写的**——插入代理头
   后要通读整个 location 块确认没有旧残留。
5. **停机拷贝清单**：MQ broker 数据目录必须停机拷（在途消息/队列状态）；
   pg 用 60s 优雅停做检查点；停机窗口的 MQ 任务由对账器重发兜底，无需
   人工补数。

### 5.2 遗留事项

| 项 | 状态 | 动作 |
|---|---|---|
| 35.208.158.36 静态化 | 未做（临时 IP） | GCP 控制台 → VPC → IP addresses → 升 Static，否则实例重启换 IP 断 DNS |
| data 机 nginx 证书 | 自签/链不全（跳板机 unknown CA） | 浏览器实测若告警，换 LE fullchain（`fullchain.pem` 而非 `cert.pem`） |
| 新 main 机 SSH 通道 | 走公网 34.28.249.164:22 | 可选：收紧到跳板机跳转（与 data 机一致经 -J） |
| Cloudflare 橙云 | 当前仅 DNS（灰云） | 若开橙云代理，SSL 模式须 Full(Strict) |
| CF DNS 缓存 | 各端残留旧 Google 解析 | TTL 到期自动消散，无需操作 |

### 5.3 基础设施资产清单（终态）

- GCP 项目 project-61141193-1395-44cc-8f4（us-central1-a）：
  - `instance-20260914-014126` e2-small 2GB / 50GB pd-balanced：main 全家
  - `instance-20260902-042706` e2-micro 1GB / 30GB pd-standard：nginx 入口 + data×2
  - 防火墙：公网仅 http/https/ssh/icmp；5672/5432/15672 仅内网
- GCP 项目 project-56325d20-bc30-4bb7-a73：Cloud Run `stock-calculator`（前端）
- 跳板机（家侧）：104.36.71.217:27532，备份 `/root/scs-backup-20260914/`
- 家域：完整独立栈（main+data+middleware，本地 broker），与云域互不相通——
  本次迁移未动家域

## 6. 运维通道（后续补建，2026-09-14 04:30Z）

**LavinMQ 管理台公网出口**：`https://mq.oklhj.eu.org`（与正式服务 scs 域名分离）

```
浏览器 → CF DNS(mq.oklhj.eu.org → 35.208.158.36) → data 机 nginx :443
        ├─ 第 1 层：nginx basic_auth（/etc/nginx/.htpasswd，admin + 随机口令）
        ├─ 第 2 层：LavinMQ 自身登录（RABBIT_USER / RABBIT_PASS）
        └─ proxy_pass → 内网 10.128.0.5:15672（WebSocket 升级头已配，3600s 长连接）
```

- 配置落点：`/etc/nginx/conf.d/mq-pg-expose.conf`（data 机），证书复用
  Cloudflare Origin 通配符 `*.oklhj.eu.org`（2040 到期）；
- 踩坑两枚：nginx worker 读 htpasswd 需 `root:www-data 640`；pg 四层代理
  （stream 模块）的 include 必须包在顶层 `stream {}` 块，放 http {} 内报
  `server directive is not allowed here`；
- **PG 代理方案已撤**：曾配 nginx stream 15432 + 防火墙放行 + pg 自签 SSL，
  因 rootless podman 的 uid 映射使容器内 postgres 进程读不了宿主 600 权限
  key（属主校验通过后 Permission denied），最终按用户决策放弃 PG 公网通道，
  全部回滚（pg 容器恢复原配置，数据无损）。**PG 远程访问需求建议走 SSH
  隧道：`ssh -L 5432:10.128.0.5:5432` 跳板机方案，不经公网。**
