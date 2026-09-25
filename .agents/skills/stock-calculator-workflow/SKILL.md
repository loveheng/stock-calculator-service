---
name: stock-calculator-workflow
description: 环境限制规避指南。在此项目中，所有终端命令和文件写入操作必须遵循本技能指定的限制与工作模式，否则命令会被自动截断或取消。
---

# stock-calculator-workflow

## 环境限制

这个项目存在以下已知的严格环境限制，**必须遵守**，否则命令会被自动截断/取消：

### 1. 单次工具调用内容长度限制
- `write_file` 和 `terminal` 工具在单次调用中**超过一定长度时会被截断**
- 截断表现为 "Tool canceled by user" 或内容被截断
- **必须**：每个文件分多次写入，或用 `cat >> file <<'EOF'` 分段追加

### 2. 终端工具禁止 `${...}` 字符串
- 终端命令中**不能包含 `${...}`** 形式的字符串（如 `${spring-ai.version}`、`${project.version}`）
- 即使是 `<<'EOF'` 引用的 heredoc 也不行——工具会静态扫描命令字符串
- **必须**：用字面量代替（如 `2.0.1` 代替 `${spring-ai.version}`）、或在 Maven pom 中硬编码版本号
- 其他 `$` 形式（如 `$VAR`、`$(...)`）同样被禁止

### 3. 命令长度限制
- 过长的单行命令或过多的参数也会被截断
- **必须**：每个命令尽量简短，分步执行

## 可靠的工作模式（已验证）

### 文件写入
```sh
# ✅ 可靠：分段写入（每段小）
cat > file <<'EOF'
content segment 1
EOF
cat >> file <<'EOF'
content segment 2
EOF
echo 'closing tag' >> file

# ❌ 不可靠：一次性写入长内容（会被截断）
cat > file <<'EOF'
...very long content...
EOF
```

### 从 Git 分支复制文件
```sh
# ✅ 可靠：单条命令，一个文件
git show branch:old/path/File.java > new/module/path/File.java

# ❌ 不可靠：多条命令放一起
git show ... && git show ...
```

### 读取文件
```sh
# ✅ 可靠：小文件用 cat
cat file

# ✅ 可靠：大文件用 head/tail
head -5 file
tail -5 file
```

### 避免 `${...}` 的替代方案
```xml
<!-- ❌ 不可靠：包含 ${...} -->
<version>${spring-ai.version}</version>

<!-- ✅ 可靠：字面量 -->
<version>2.0.1</version>
```

## 项目特定信息

### 模块结构
```
stock-calculator-service/            (父 POM，packaging=pom，BOM 聚合；分支 dev)
├─ stock-calculator-contract/       (MQ 契约：MqKey/MqQueue/消息 payload 常量，
│                                    被 main 与 data 共同依赖)
├─ stock-calculator-main/           (主服务 :18080：领域包 announcement/auth/copilot/
│                                    crawler/customstat/llm/monitor/search/sync/vision +
│                                    共享 common/config/util；native 脚本 build-native.sh/
│                                    gen-logger-config.py/record-agent.sh/package-native.sh)
└─ stock-calculator-data/           (数据服务副本：MQ 拉取循环与公告/向量化 worker
                                      （announcement/cls/config/hello/ingest/llm/mq/worker 包）；
                                      自带 native 构建 build-native.sh/Dockerfile.native/
                                      smoke-native.sh)
```

历史：stock-calculator-native 已于 2026-08-31 删除；stock-calculator-common 已于 2026-09-01
并入 main；2026-09-12 拆分主/数据模块（commit 4c70110），main↔data 经 MQ 通信（contract
模块承载契约），main 内部域边界仍由 ModulithVerifyTest 守护。

### 包名
- main：`com.zzh.stock_calculator`
- data：`com.zzh.stock_calculator.data`
- contract：`com.zzh.stockcalc.contract`（注意无下划线，历史遗留）

### 环境与工具链

- GraalVM 25.0.4（含 native-image / javap / native-image-agent）安装在 `/opt/GraalVM25`；sdkman 已卸载
- 系统 java 是 Ubuntu OpenJDK 21：无 native-image 工具链、无 agent 库——native 构建 / agent 录制 / javap 一律用 `/opt/GraalVM25/bin/` 下对应工具
- 构建一律用仓库根 `./mvnw`，不用系统 mvn；脚本探测顺序惯例 `JAVA_HOME` → `/opt/GraalVM25` → `PATH`

### 本地依赖服务

- 中间件以 compose 常驻，定义与起停见仓库根 `docker-compose.middleware.yml` 文件头注释（唯一事实源）：postgres（pgvector/pg16，:5432）、redis（:6379）、lavinmq（AMQP :5672 / 管理台 :15672）+ 一次性建号容器 lavinmq-init；固定网络 scs-net，应用层经 `docker-compose.app.yml` 接入
- 口令一律在项目根 `.env`（已 gitignore，不提交）：`POSTGRES_PASS`（应用侧变量名；postgres 镜像初始化另认 `POSTGRES_PASSWORD`，两个都写在 .env，别混用）、`RABBIT_USER`（默认 scs）/`RABBIT_PASS`——LavinMQ 不读环境变量，账号由 lavinmq-init 经 control socket 建立
- @SpringBootTest / E2E / native 冒烟均依赖上述容器存活且健康；口令从 `.env` 取，严禁把明文写进仓库或 skill

### 构建命令
```sh
# 全仓编译（根目录，reactor 覆盖三模块）
./mvnw compile

# 按模块单测（@SpringBootTest 需本地 PG + RabbitMQ 先起）
POSTGRES_PASS=... ./mvnw test -pl stock-calculator-main
POSTGRES_PASS=... ./mvnw test -pl stock-calculator-data

# 全部 E2E（RABBIT_E2E=true 门控，默认套件自动跳过；需本地 broker 已起）
RABBIT_E2E=true POSTGRES_PASS=... ./mvnw test -pl stock-calculator-main -Dtest='*E2ETest'
RABBIT_E2E=true POSTGRES_PASS=... ./mvnw test -pl stock-calculator-data -Dtest='*E2ETest'

# 无本地 DB 跑 main 单测（排除两个 @SpringBootTest）
./mvnw test -pl stock-calculator-main '-Dtest=!StockCalculatorApplicationTests,!SyncBackupL1IntegrationTest' '-DfailIfNoTests=false'
```

（TaskServiceTest 已随 MQ 化改造删除，历史文档里的 `-Dtest=!TaskServiceTest` 排除项
失效——无害但过时，存量 docs 多处引用属待修文档债）