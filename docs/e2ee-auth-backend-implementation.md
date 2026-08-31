# E2EE 用户服务后端 · 实行方案

> 版本：v1.0（2026-08-31）
> 前置：设计方案 docs/e2ee-auth-backend-design.md（下称《设计》）契约冻结后执行；章节引用 §D.x 指《设计》章节。
> 验证基线：./mvnw compile -pl stock-calculator-main -am -q 与 ./mvnw test -pl stock-calculator-main -am；native 回归：./mvnw compile -pl stock-calculator-native -am -q

---

## 1. 阶段总览

| 阶段 | 内容 | 产出 | 验收标准 | 依赖 |
|------|------|------|----------|------|
| B0 | 契约冻结 | 《设计》定稿 + 《前端 spec》§5.1/§8/§11 三节修订 | 前后端各自认领端点 | 无 |
| B1 | 核心鉴权 | 4 表 DDL + 会话模型 + register/login/logout/profile 四组端点 | §5 冒烟脚本全过 | B0 |
| B2 | 找回闭环 | mail + OTP + recovery 三端点 + 原子 confirm | 真实邮箱收码全链路 | B1 |
| B3 | 加固验证 | 限流 / If-Match / native 隔离 / 单测 | 测试全绿 + 前端联调 | B2 |

> 与前端 Phase 的并行关系：前端 Phase 1（密码学）与 Phase 2（状态机）不依赖后端，可立即并行；后端 B1 需在前端 Phase 3（UI Modal）联调前就绪，B2 在找回流程联调前就绪。

---

## 2. B1 核心鉴权 · 详细任务

### 2.1 依赖变更（stock-calculator-common/pom.xml，仅 1 个）

| 构件 | 用途 | 说明 |
|------|------|------|
| spring-security-crypto | BCryptPasswordEncoder | 独立构件、无 Security 过滤链、无自动装配副作用；版本随 spring-boot-dependencies 4.1.1 管理 |

> spring-boot-starter-mail 在 B2 再引入，保持 B1 最小面。

### 2.2 DDL 追加（postgres/schema.sql 尾部，手工执行）

```sql
-- ============================================================
-- E2EE 用户服务（《设计》§D.3）；IF NOT EXISTS 幂等，无需停机
-- ============================================================
CREATE TABLE IF NOT EXISTS public.users (
    id uuid NOT NULL,
    email varchar(255) NOT NULL,
    password_hash varchar(60) NOT NULL,
    created_at timestamptz DEFAULT CURRENT_TIMESTAMP NULL,
    updated_at timestamptz DEFAULT CURRENT_TIMESTAMP NULL,
    CONSTRAINT users_pkey PRIMARY KEY (id),
    CONSTRAINT users_email_key UNIQUE (email)
);

CREATE TABLE IF NOT EXISTS public.user_profiles (
    id uuid NOT NULL,
    password_payload text NOT NULL,
    password_iv varchar(32) NOT NULL,
    recovery_payload text NOT NULL,
    recovery_iv varchar(32) NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at timestamptz DEFAULT CURRENT_TIMESTAMP NULL,
    CONSTRAINT user_profiles_pkey PRIMARY KEY (id),
    CONSTRAINT user_profiles_id_fkey FOREIGN KEY (id)
        REFERENCES public.users (id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS public.auth_sessions (
    id uuid NOT NULL DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL,
    token_hash varchar(64) NOT NULL,
    scope varchar(16) NOT NULL DEFAULT 'full',
    expires_at timestamptz NOT NULL,
    last_seen_at timestamptz NULL,
    revoked_at timestamptz NULL,
    created_at timestamptz DEFAULT CURRENT_TIMESTAMP NULL,
    CONSTRAINT auth_sessions_pkey PRIMARY KEY (id),
    CONSTRAINT auth_sessions_token_hash_key UNIQUE (token_hash)
);
CREATE INDEX IF NOT EXISTS idx_auth_sessions_user_id ON public.auth_sessions (user_id);
CREATE INDEX IF NOT EXISTS idx_auth_sessions_expires_at ON public.auth_sessions (expires_at);

CREATE TABLE IF NOT EXISTS public.otp_codes (
    id bigserial NOT NULL,
    email varchar(255) NOT NULL,
    code_hash varchar(64) NOT NULL,
    purpose varchar(16) NOT NULL DEFAULT 'recovery',
    attempts int4 NOT NULL DEFAULT 0,
    expires_at timestamptz NOT NULL,
    consumed_at timestamptz NULL,
    created_at timestamptz DEFAULT CURRENT_TIMESTAMP NULL,
    CONSTRAINT otp_codes_pkey PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_otp_codes_email ON public.otp_codes (email);
```

> gen_random_uuid() 为 PostgreSQL 13+ 内置函数，pgvector:pg16 镜像直接可用，无需 pgcrypto 扩展。

### 2.3 文件清单 · stock-calculator-common（全部新增，不触碰现有文件）

| 文件 | 职责 | 关键点 |
|------|------|--------|
| entity/UserEntity.java | → public.users | id 手写 uuid；无敏感字段 |
| entity/UserProfileEntity.java | → public.user_profiles | 四密文列 + updatedAt（兼 If-Match 版本号） |
| entity/AuthSessionEntity.java | → public.auth_sessions | tokenHash / scope / expiresAt / revokedAt；id Java 侧 UUID.randomUUID() |
| entity/OtpCodeEntity.java | → public.otp_codes | codeHash / attempts / consumedAt；自增主键 IDENTITY |
| repository/UserRepository.java | existsByEmail / findByEmail | |
| repository/UserProfileRepository.java | findById 即按 user 查（主键同 user id） | |
| repository/AuthSessionRepository.java | findByTokenHash / findByUserIdAndRevokedAtIsNull | |
| repository/OtpCodeRepository.java | findFirstByEmailOrderByCreatedAtDesc | 冷却检查用 |
| dto/auth/AuthSessionResponse.java | {userId, token, expiresAt, hasProfile?} | Lombok 三件套 |
| dto/auth/RegisterRequest.java | {email, password} | password 字段 @ToString.Exclude |
| dto/auth/LoginRequest.java | {email, password, ttlDays} | 同上 |
| dto/auth/ProfileResponse.java | 四密文 + updatedAt | 对齐《前端 spec》§4.1 字段语义 |
| dto/auth/ProfileUpsertRequest.java | 四密文 | B3 阶段补 Base64/非空校验 |
| dto/auth/RecoveryConfirmRequest.java | {newPassword, passwordPayload, passwordIv} | password 字段 @ToString.Exclude |
| service/SessionService.java | issue / resolve / revokeCurrent / revokeAllOthers | 签名见 §2.4 |
| service/AuthService.java | register / login / logout | 签名见 §2.4 |
| service/ProfileService.java | get / upsertIfMatch | 签名见 §2.4 |
| common/AuthErrorCode.java | 401 / 404 / 409 / 429 语义常量 | 配合 BusinessException 使用 |

> 实体通用模板（Lombok 三件套顺序、@CreationTimestamp/@UpdateTimestamp、timestamptz → OffsetDateTime、外键平铺不建关联）按项目既定模式执行。

### 2.4 核心方法签名（实现要点）

```java
// SessionService（《设计》§D.4.3）
SessionIssue issue(UUID userId, String scope, int ttlDays);
//  SecureRandom 32B → base64url 令牌；SHA-256(token) 落库；expiresAt = now + ttl
Optional<AuthSessionEntity> resolve(String bearer);
//  SHA-256(bearer) → findByTokenHash → 校验 revoked / expires →
//  节流续期：lastSeenAt 距今超过 TTL/2 时顺延 expiresAt 与 lastSeenAt
void revokeCurrent(String bearer);
void revokeAllOthers(UUID userId, String currentBearer);
```

```java
// AuthService
AuthSessionResponse register(RegisterRequest req);
//  normalizeEmail → existsByEmail ? 409 → password 64hex 校验 → bcrypt(10)
//  → insert users → issue(full, 7)
AuthSessionResponse login(LoginRequest req);
//  normalizeEmail → findByEmail → bcrypt.matches
//  （用户不存在也要跑一次 dummy matches 抹平时序）
//  → issue(full, ttlDays 默认 7、上限 30) → 附带 hasProfile
void logout(String bearer);

// ProfileService
ProfileResponse get(UUID userId);   // 无行 → BusinessException(404)
ProfileResponse upsert(UUID userId, ProfileUpsertRequest req, String ifMatchHeader);
//  无行 → 无条件创建；有行 → ifMatch 必须等于该行 updatedAt（ISO-8601）
//  缺失或不符 → BusinessException(409)，data 携带服务端 updatedAt
```

### 2.5 文件清单 · stock-calculator-main

| 文件 | 职责 | 关键点 |
|------|------|--------|
| controller/AuthController.java | 8 端点（《设计》§D.4.2） | @CrossOrigin(origins = "*") 沿用 ImportController 先例；@RequestMapping("/api/auth") |
| config/AuthInterceptor.java | Bearer 解析 → SessionService.resolve → 写 request attribute（userId / scope） | 失败写 HTTP 401 + ApiResponse 体；recovery scope 仅放行 confirm 与 logout |
| config/WebConfig.java | 注册拦截器于 /api/auth/** | 排除 register / login / recovery/request / recovery/verify（无会话端点） |
| config/AuthProperties.java | ttl 上限、recovery TTL、OTP 参数 | @ConfigurationProperties("app.auth") |
| application.yml | app.auth.enabled: true + app.auth.* + spring.mail（B2） | main 显式开启；native 不配置即默认关闭 |

---

## 3. B2 找回闭环 · 详细任务

### 3.1 依赖与配置

| 项 | 内容 |
|----|------|
| common/pom.xml | + spring-boot-starter-mail |
| application.yml | spring.mail.host / port / username / password + 默认发件人（占位符写法对齐 application.yml 现有 GEMINI_API_KEY 条目，凭据经 docker-compose 环境变量注入） |

### 3.2 文件清单（common）

| 文件 | 职责 | 关键点 |
|------|------|--------|
| service/MailService.java | 发验证码邮件 | JavaMailSender；虚拟线程已开，阻塞发送无需异步改造；模板：标题含品牌、正文含 6 位码 + "10 分钟内有效，非本人操作请忽略" |
| service/OtpService.java | issue / verify | issue：同邮箱 60s 冷却（查最近一条 created_at）→ SecureRandom 6 位 → SHA-256 落库 → 发信；verify：未过期 + attempts 小于 5 + 未消费 + 常量时间比较 → 置 consumed_at；校验失败 attempts 加 1 |

### 3.3 recovery 三端点接入 AuthController

| 端点 | 事务 | 说明 |
|------|------|------|
| POST /api/auth/recovery/request | 无 | 恒 200；邮箱不存在静默不发（《设计》§D.5.2） |
| POST /api/auth/recovery/verify | 单写 | 校验通过 → SessionService.issue(user, "recovery", 短 TTL 10 分钟) |
| POST /api/auth/recovery/confirm | **@Transactional 单事务** | ① resolve recovery 会话 → ② bcrypt 新 authHash 更新 users → ③ 更新 user_profiles.password_payload / password_iv（updatedAt 刷新）→ ④ revokeAllOthers → ⑤ 签发全量新会话；任一步失败整体回滚（决策 B6） |

> 事务边界注意：⑤ 若放事务内，回滚会连带作废新会话；实现时 ①-④ 在事务方法内，⑤ 在事务提交后执行。

---

## 4. B3 加固与验证 · 详细任务

### 4.1 限流（RateLimitInterceptor）

- Caffeine 缓存 key = 维度组合（IP 取 X-Forwarded-For 首跳，回退 remoteAddr）；
- 阈值按《设计》§D.5.4 矩阵；超限抛 BusinessException(429, "尝试次数过多，请稍后再试")；
- 注册于 WebConfig，顺序：RateLimitInterceptor → AuthInterceptor。

### 4.2 If-Match 完整化 + 入参校验

- ProfileService.upsertIfMatch 三分支：无行首建 / 有行匹配 / 有行不匹配（409 + data.updatedAt）；
- ProfileUpsertRequest 校验四列非空且 Base64 可解码，防脏数据落库。

### 4.3 native 变体隔离验证

- common 层鉴权 @Service 类统一标注 @ConditionalOnProperty(name = "app.auth.enabled", havingValue = "true")；
- main 的 application.yml 显式开启；native 不配置 → Bean 不装配；
- 回归命令：./mvnw compile -pl stock-calculator-native -am -q（编译通过即可，本期不在 native 联调鉴权）。

### 4.4 单测清单（风格对齐 TaskServiceTest：Mockito + 断言，不开 Spring 上下文）

| 测试类 | 覆盖 |
|--------|------|
| AuthServiceTest | 注册成功 / 重复 409 / password 非 64hex 400 / 登录成功 / 密码错 / 用户不存在（dummy bcrypt 时序抹平路径）/ 邮箱归一化等价（大小写、首尾空格） |
| SessionServiceTest | 签发-解析回环 / 过期 401 / revoked 401 / 续期节流（TTL 半程前后行为）/ recovery scope 拦截（持 recovery 会话调 profile 应拒绝） |
| ProfileServiceTest | 首建 / 更新 / If-Match 缺失 409 / 不匹配 409 / 匹配通过 |
| OtpServiceTest | 60s 冷却 / 过期 / 5 次锁死 / 单次消费 / 校验失败计数 |
| AuthControllerTest（MockMvc，可选） | 401 体格式 / 404 缺行语义 / 409 双语义 message 区分 |

### 4.5 全链路冒烟（部署后，curl 顺序）

1. register → 200 拿 token；2. PUT profile（首建）→ 200；3. GET profile → 200 四元组；4. logout → 200；5. 旧 token GET profile → 401；6. login → 200；7. recovery/request → 邮箱收码；8. verify → recovery token；9. confirm → 新全量 token；10. 步骤 6 的旧 token → 401（他端吊销生效）。

---

## 5. 部署清单（上线顺序）

| 步骤 | 操作 | 载体 |
|------|------|------|
| 1 | 手工执行 postgres/schema.sql 增量（4 表，幂等） | 服务器 psql |
| 2 | docker-compose 增 SMTP_HOST / SMTP_PORT / SMTP_USERNAME / SMTP_PASSWORD / MAIL_FROM 环境变量 | docker-compose.yml |
| 3 | 重建镜像并部署（Dockerfile 无需改动，已构建 main 变体） | Dockerfile / compose |
| 4 | Edge Middleware 白名单加 /api/auth；vite dev proxy 同步 | 前端仓库 middleware.js / vite.config.ts |
| 5 | 《前端 spec》三节修订 + 薄适配器实现（前端侧任务） | 前端仓库 docs/e2ee-auth-spec.md |
| 6 | 全链路冒烟（§4.5）+ 前端联调（注册→备份→登出→登录→锁屏→解锁→找回） | 双端 |

---

## 6. 风险回查表（对策落点）

| 风险（前评估分级） | 对策 | 落点 |
|--------------------|------|------|
| P0 会话吊销模型 | 不透明令牌 + auth_sessions 吊销列 | SessionService（《设计》§D.4.3） |
| P0 零知识红线 | @ToString.Exclude + 日志纪律 + 64hex 校验 | DTO / AuthService（《设计》§D.5.1 / 5.3） |
| P0 OTP 弱实现 | 冷却 / 限次 / 单次 / 哈希 / 限流五件套 | OtpService（《设计》§D.5.2） |
| P1 profile 覆盖竞态 | If-Match → 409 | ProfileService（《设计》§D.4.4 / §D.6.4） |
| P1 找回失配窗口 | confirm 单事务原子化 | AuthService（《设计》§D.6.5） |
| P1 SMTP 可达性 | 生产 SMTP + SPF/DKIM；"邮箱码 + 助记词"双因子缺失即无法找回，需文档明示 | 部署清单步骤 2 |
| P1 native 污染 | @ConditionalOnProperty 隔离 + 回归编译 | §4.3 |
| P2 限流重启清零 / IP 伪造边界 | 已记录取舍 | RateLimitInterceptor 注释 |

---

## 7. 完成定义（DoD）

- [ ] ./mvnw compile -pl stock-calculator-main -am 与 ./mvnw compile -pl stock-calculator-native -am 均通过
- [ ] ./mvnw test 全绿（含 §4.4 全部测试类）
- [ ] §4.5 冒烟 10 步全过 + 真实邮箱收码
- [ ] 前端全链路联调通过（对齐《前端 spec》§12.2 步骤 8 的用户旅程）
- [ ] postgres/schema.sql 增量已入库；README 部署提示更新
- [ ] 《前端 spec》§5.1 / §8 / §11 修订同步完成
