# 服务端密文同步 · 后端开发实施文档

> 版本：v1.0（2026-09-04，对应 `server-sync-backend-design.md` v1.0 修正 E1-E9）
> 范围：sync 领域包全部文件骨架、schema.sql 增量、WebConfig 接线、单测与真库冒烟、验证命令与里程碑
> 关联：`docs/server-sync-backend-design.md`（设计与修正表 E1-E9）、前端仓库 `docs/server-sync-implementation.md`（前端落点）；skill `cls-article-patterns`（编码模板）、`stock-calculator-workflow`（环境限制）
> 状态：待开发

---

## 0. 已核实现状基线（开发前事实，与前端文档不同处已按 design E1-E9 修正）

| 事实 | 证据（本仓库） |
|---|---|
| 用户主键是 UUID，不是 BIGINT（E1） | `auth/entity/UserEntity.java`：`@Id private UUID id`；schema.sql `auth_sessions.user_id uuid` |
| copilot 用户列先例 varchar(64) + String | schema.sql `ai_chat_session.user_id VARCHAR(64)`；`AiChatSession.userId` String |
| userId 注入方式 | `AuthInterceptor`：`request.setAttribute("authUserId", …)`；`CopilotController`：`@RequestAttribute("authUserId") String userId`（Spring 自动 UUID→String 转换） |
| 成功码恒 200（E5） | `common/ApiResponse.success` → code=200；前端 `apiClient.ts` 判 `envelope.code === 200` |
| BusinessException 无 data 通道（E4） | `common/BusinessException.java` 仅 code+message；`ApiResponse.fail` 恒 data=null；`GlobalExceptionHandler.handleBusinessException` 转恒 200 信封 |
| 401 出口唯一 | `AuthInterceptor.reject`：HTTP 401 + `ApiResponse.fail(AuthErrorCode.UNAUTHORIZED, …)` |
| 拦截路径白名单（E3） | `auth/config/WebConfig`：现挂 `/api/auth/profile/**`、`/api/auth/logout`、`/api/auth/recovery/confirm`、`/api/copilot/**`；类上 `@ConditionalOnProperty(name="app.auth.enabled", havingValue="true")` |
| JSON 栈为 Jackson 3 | `tools.jackson.databind.ObjectMapper`（Boot 4 自动装配 Bean，AuthInterceptor 注入同款） |
| 表结构手工管理 | `spring.jpa.hibernate.ddl-auto: none`；仓库根 `postgres/schema.sql`（`public.` 前缀、缩写类型、`IF NOT EXISTS`、显式约束名） |
| 测试基建 | 纯 Mockito 单测（`auth/service/SessionServiceTest` 模式）；无 H2 / Testcontainers；`@SpringBootTest` contextLoads 需本地 PG 环境变量 |
| 验证命令 | `./mvnw compile -q`；`POSTGRES_PASS=… ./mvnw install '-Dtest=!TaskServiceTest' '-DfailIfNoTests=false'` |

**环境约束**：文件分段写入；终端命令禁含 `${...}` 字符串——Service 的 `@Value` defaultValue 含 `${...}`，该文件必须用 write_file 写入，禁止终端 heredoc；全量验证永远排除 TaskServiceTest。

## 1. 文件清单

```
stock-calculator-main/src/main/java/com/zzh/stock_calculator/sync/
├── controller/SyncBackupController.java    # 3 端点 + 带 data 响应组装（E4）
├── dto/SyncDtos.java                       # 4 DTO + PushOutcome + RateLimitData
├── entity/UserSyncData.java                # 主表：每用户一行
├── entity/UserSyncHistory.java             # 历史表：被替换版本
├── repository/UserSyncDataRepository.java  # casUpsert + selectVersion 回读（E2）
├── repository/UserSyncHistoryRepository.java
└── service/SyncBackupService.java          # 校验/去重/频控/CAS/历史

stock-calculator-main/src/test/java/com/zzh/stock_calculator/sync/service/
└── SyncBackupServiceTest.java

改动既有文件（2 处）：
├── auth/config/WebConfig.java              # +1 行：/api/sync/**（E3，§8）
└── postgres/schema.sql                     # 追加两表（§2）
```

Modulith：sync 只依赖 `common` 基包；userId 经 `@RequestAttribute` 注入，不 import auth/其他域任何类型。

## 2. schema.sql 增量（追加到仓库根 postgres/schema.sql）

```sql
-- ============================================================
-- 服务端密文同步（server-sync-backend-design.md §3 / D5 / D8 / D10 / D11 / E1 / E7）
-- ============================================================
CREATE TABLE IF NOT EXISTS public.user_sync_data (
	user_id           varchar(64) NOT NULL,
	encrypted_payload text NOT NULL,
	version           int8 NOT NULL,
	payload_hash      varchar(64) NULL,
	payload_bytes     int4 NOT NULL,
	created_at        timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
	updated_at        timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
	CONSTRAINT user_sync_data_pkey PRIMARY KEY (user_id)
);

CREATE TABLE IF NOT EXISTS public.user_sync_history (
	id                bigserial NOT NULL,
	user_id           varchar(64) NOT NULL,
	version           int8 NOT NULL,
	encrypted_payload text NOT NULL,
	payload_bytes     int4 NOT NULL,
	created_at        timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
	CONSTRAINT user_sync_history_pkey PRIMARY KEY (id),
	CONSTRAINT uq_user_sync_history UNIQUE (user_id, version)
);

-- 回滚：DROP TABLE IF EXISTS public.user_sync_history; DROP TABLE IF EXISTS public.user_sync_data;
-- 历史裁剪规则（service 层）：成功写入 newVersion 后 DELETE version < newVersion - 5（D8 + E10，保留 {N-5..N-1} 恰 5 份）
-- 历史唯一冲突由 INSERT … ON CONFLICT DO NOTHING 吸收（E7，整库回滚场景）
```

## 3. Entity（Lombok 三件套，按 cls-article-patterns）

```java
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "user_sync_data")
public class UserSyncData {

    /** 外部ID（authUserId，UUID 文本），手动写入（E1：String 非 Long） */
    @Id
    @Column(name = "user_id", nullable = false, length = 64)
    private String userId;

    @Column(name = "encrypted_payload", nullable = false, columnDefinition = "TEXT")
    private String encryptedPayload;

    /** 服务端单调自增（D5）：INSERT 首传 1，覆盖 CAS +1；Java 侧永不手动改 */
    @Column(name = "version", nullable = false)
    private Long version;

    /** DB 可空、API 必填（E8）；仅去重行内比对 */
    @Column(name = "payload_hash", length = 64)
    private String payloadHash;

    @Column(name = "payload_bytes", nullable = false)
    private Integer payloadBytes;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    /** 频控时钟（D10）；CAS 走 SQL NOW() 直改 DB，下次读实体即新值 */
    @UpdateTimestamp
    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;
}
```

```java
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "user_sync_history",
       uniqueConstraints = @UniqueConstraint(name = "uq_user_sync_history",
               columnNames = {"user_id", "version"}))
public class UserSyncHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, length = 64)
    private String userId;

    /** 被替换时的云端版本号 */
    @Column(name = "version", nullable = false)
    private Long version;

    @Column(name = "encrypted_payload", nullable = false, columnDefinition = "TEXT")
    private String encryptedPayload;

    @Column(name = "payload_bytes", nullable = false)
    private Integer payloadBytes;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;
}
```

平铺字段、无 `@ManyToOne`/`@OneToMany`；TIMESTAMPTZ → OffsetDateTime（pattern 3.4）。

## 4. Repository（CAS 核心 + 回读，E2/E7）

```java
@Repository
public interface UserSyncDataRepository extends JpaRepository<UserSyncData, String> {

    /**
     * 乐观 CAS upsert（design §5.1）：返回 1=写入成功；0=冲突。
     * 必须在 @Transactional 内调用（service 层保证）。
     */
    @Modifying
    @Query(value = """
            INSERT INTO user_sync_data (user_id, encrypted_payload, version, payload_hash, payload_bytes)
            VALUES (:userId, :payload, 1, :hash, :bytes)
            ON CONFLICT (user_id) DO UPDATE SET
                encrypted_payload = EXCLUDED.encrypted_payload,
                version           = user_sync_data.version + 1,
                payload_hash      = EXCLUDED.payload_hash,
                payload_bytes     = EXCLUDED.payload_bytes,
                updated_at        = NOW()
            WHERE user_sync_data.version = :baseVersion
            """, nativeQuery = true)
    int casUpsert(@Param("userId") String userId,
                  @Param("payload") String payload,
                  @Param("hash") String hash,
                  @Param("bytes") Integer bytes,
                  @Param("baseVersion") Long baseVersion);

    /**
     * CAS 成功后回读实际版本（E2）。
     * 必须 native：findById 会命中一级缓存返回 CAS 前旧实体；
     * 同事务 READ COMMITTED 可见自身未提交写入，回读值即本次写入值。
     */
    @Query(value = "SELECT version FROM user_sync_data WHERE user_id = :userId", nativeQuery = true)
    Long selectVersion(@Param("userId") String userId);
}
```

```java
@Repository
public interface UserSyncHistoryRepository extends JpaRepository<UserSyncHistory, Long> {

    /** E7：唯一冲突静默吸收（整库回滚后重推同版本），返回 0 不影响主流程。
     *  必须 native：冲突吸收是 PG 方言行为（H2 语义有差异，禁用 H2 跑相关用例） */
    @Modifying
    @Query(value = """
            INSERT INTO user_sync_history (user_id, version, encrypted_payload, payload_bytes)
            VALUES (:userId, :version, :payload, :bytes)
            ON CONFLICT DO NOTHING
            """, nativeQuery = true)
    int insertIgnore(@Param("userId") String userId,
                     @Param("version") Long version,
                     @Param("payload") String payload,
                     @Param("bytes") Integer bytes);

    /** D8 + E10 裁剪：DELETE version < newVersion - 5，保留 {N-5..N-1} 恰 5 份 */
    void deleteByUserIdAndVersionLessThan(String userId, Long version);
}
```

注意：`deleteByUserIdAndVersionLessThan` 是派生删除，同样需事务（service 已保证）。

## 5. DTO（dto/SyncDtos.java，Lombok 三件套 + 静态工厂）

| DTO | 字段 | 静态工厂 |
|---|---|---|
| SyncMetaDto | hasData boolean / version Long / updatedAt OffsetDateTime / payloadHash String / payloadBytes Integer | `empty()`（hasData=false, version=0）/ `of(UserSyncData)`（version 复制入参可覆盖，见下） |
| SyncPullDto | version / updatedAt / payloadHash / envelope | `of(UserSyncData)` |
| SyncPushRequest | baseVersion Long / envelope String / payloadHash String / payloadBytes Integer | 无（@RequestBody 反序列化） |
| SyncPushResultDto | version Long / deduped boolean | `of(version, deduped)` |
| PushOutcome | type（OK/CONFLICT/RATED）/ version / deduped / meta SyncMetaDto / emptyConflict boolean / retryAfterSeconds Integer | `ok(v, deduped)` / `conflict(meta, emptyConflict)` / `rated(retryAfter)` |
| RateLimitData | retryAfterSeconds Integer | `of(int)` |

- PushOutcome 是 service 内部判别结果（E4）：冲突/频控不抛异常，由 Controller 组装带 data 响应
- `updatedAt` 序列化：Jackson 3 默认 ISO-8601（OffsetDateTime 带偏移），前端 `new Date()` 可解析，无需额外配置
- 全部 public static 内部类（仿 CopilotDtos 组织）

## 6. Service（SyncBackupService 完整骨架）

```java
@Slf4j
@Service
@RequiredArgsConstructor
public class SyncBackupService {

    private static final int HISTORY_KEEP = 5;                 // D8
    private static final long EMPTY_BASE_VERSION = 0L;         // baseVersion=0 = 云端应为空（D5）

    // D11：defaultValue 写死，不进 yml（本文件含 ${...}，必须用 write_file 写入，禁终端 heredoc）。
    // 频控窗口参数化：生产默认 5s 不变；测试以属性覆盖窗口（如 1ms）支持连续推送（§9.2）
    @Value("${sync.push.rate-limit-millis:5000}")
    private long rateLimitMillis;

    @Value("${sync.push.max-envelope-bytes:2000000}")
    private int maxEnvelopeBytes;

    private final UserSyncDataRepository dataRepository;
    private final UserSyncHistoryRepository historyRepository;
    private final ObjectMapper objectMapper;   // tools.jackson，Boot 自动装配 Bean

    /** 元信息对账（D13 轻量轮询的基础） */
    @Transactional(readOnly = true)
    public SyncMetaDto meta(String userId) {
        return dataRepository.findById(userId)
                .<SyncMetaDto>map(SyncMetaDto::of)
                .orElseGet(SyncMetaDto::empty);
    }

    /** 拉取密文（原样透传，不解密）；云端无备份 → 40401（data=null，走 GlobalExceptionHandler） */
    @Transactional(readOnly = true)
    public SyncPullDto pull(String userId) {
        UserSyncData e = dataRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(40401, "云端暂无备份"));
        return SyncPullDto.of(e);
    }

    /** 上传：校验 → 去重 → 频控 → CAS → 历史 → 回读（design §4.4 顺序） */
    @Transactional
    public PushOutcome push(String userId, SyncPushRequest req) {
        validateEnvelope(req);                                   // 40001/40002/40003
        UserSyncData current = dataRepository.findById(userId).orElse(null);

        // 去重（D7 两分支，先于频控）：hash 已由校验保证非空
        if (current != null && req.getPayloadHash().equals(current.getPayloadHash())) {
            long v = current.getVersion();
            if (v == req.getBaseVersion() || v == req.getBaseVersion() + 1) {
                return PushOutcome.ok(v, true);                  // deduped，未写库
            }
        }

        // 频控（D10）：updated_at 距今 < 窗口 → 42901，无豁免逻辑：
        // 409 后的重推是新信封（hash 不同）不命中去重，窗口内撞上属预期（E9，前端按建议重试）
        if (current != null && current.getUpdatedAt() != null) {
            long elapsed = System.currentTimeMillis()
                    - current.getUpdatedAt().toInstant().toEpochMilli();
            if (elapsed < rateLimitMillis) {
                long retryAfter = Math.max(1L,
                        (rateLimitMillis - elapsed + 999) / 1000);
                return PushOutcome.rated((int) retryAfter);
            }
        }

        // CAS 写入（D5）：0 行 = 冲突
        int affected = dataRepository.casUpsert(userId, req.getEnvelope(),
                req.getPayloadHash(), req.getPayloadBytes(), req.getBaseVersion());
        if (affected == 0) {
            boolean emptyConflict = req.getBaseVersion() == EMPTY_BASE_VERSION;   // 40902
            return PushOutcome.conflict(latestMeta(userId, current), emptyConflict);
        }

        // 回读实际版本（E2：INSERT 路径=1；推算 base+1 在云端空但 base>0 时会错报）
        long newVersion = dataRepository.selectVersion(userId);

        // 历史（D8 + E7）：CAS 成功且覆盖语义才落；唯一冲突静默吸收
        if (current != null) {
            historyRepository.insertIgnore(userId, current.getVersion(),
                    current.getEncryptedPayload(), current.getPayloadBytes());
            historyRepository.deleteByUserIdAndVersionLessThan(userId,
                    newVersion - HISTORY_KEEP);   // delete < N-5，保留恰 5 份（E10）
        }
        log.info("sync push ok, userId={}, base={}, new={}, bytes={}, hash={}",
                userId, req.getBaseVersion(), newVersion, req.getPayloadBytes(),
                req.getPayloadHash().substring(0, 8));           // 仅 hash 前 8 位（design §6）
        return PushOutcome.ok(newVersion, false);
    }

    /** 冲突时的最新 meta：version 用 native 回读保证新鲜（唯一参与客户端判定的字段）；
     *  其余展示字段取事务内 current（可能略旧，最坏客户端多一轮收敛，见 design §5.2） */
    private SyncMetaDto latestMeta(String userId, UserSyncData current) {
        if (current == null) {                                   // 并发首传冲突：行已由他人插入，现读
            return dataRepository.findById(userId)
                    .map(SyncMetaDto::of).orElseGet(SyncMetaDto::empty);
        }
        Long v = dataRepository.selectVersion(userId);
        return SyncMetaDto.of(current, v != null ? v : current.getVersion());
    }

    /** 信封校验（design §4.4 顺序；D2 只验结构不解码内容） */
    private void validateEnvelope(SyncPushRequest req) {
        if (req.getBaseVersion() == null || req.getBaseVersion() < 0) {
            throw new BusinessException(40001, "baseVersion 非法");        // E8
        }
        if (req.getPayloadHash() == null
                || !req.getPayloadHash().matches("[0-9a-f]{64}")) {
            throw new BusinessException(40003, "payloadHash 非法");        // E8
        }
        if (req.getEnvelope() == null
                || req.getEnvelope().getBytes(StandardCharsets.UTF_8).length > maxEnvelopeBytes) {
            throw new BusinessException(40002, "信封超限");                // D11
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(req.getEnvelope());
        } catch (Exception e) {
            throw new BusinessException(40001, "信封结构非法");
        }
        JsonNode v = root.get("v"), alg = root.get("alg"),
                 iv = root.get("iv"), ct = root.get("ct");
        boolean ok = root.isObject()
                && v != null && v.isNumber() && v.asInt() == 1
                && alg != null && alg.isTextual() && "A256GCM".equals(alg.asText())
                && iv != null && iv.isTextual() && !iv.asText().isEmpty()
                && ct != null && ct.isTextual() && !ct.asText().isEmpty();
        if (!ok) {
            throw new BusinessException(40001, "信封结构非法");            // D2
        }
        if (req.getPayloadBytes() == null
                || req.getPayloadBytes() != req.getEnvelope()
                        .getBytes(StandardCharsets.UTF_8).length) {
            throw new BusinessException(40001, "payloadBytes 与实际不一致");
        }
    }
}
```

## 7. Controller（SyncBackupController）

```java
@Slf4j
@RestController
@RequestMapping("/api/sync/backup")
@RequiredArgsConstructor
public class SyncBackupController {

    private final SyncBackupService syncBackupService;

    @GetMapping("/meta")
    public ApiResponse<SyncMetaDto> meta(@RequestAttribute("authUserId") String userId) {
        return ApiResponse.success(syncBackupService.meta(userId));
    }

    @GetMapping
    public ApiResponse<SyncPullDto> pull(@RequestAttribute("authUserId") String userId) {
        return ApiResponse.success(syncBackupService.pull(userId));   // 40401 走全局异常处理
    }

    /** E4：OK → success；CONFLICT/RATED 由 Controller 组装带 data 响应（BusinessException 无 data 通道） */
    @PutMapping
    public ApiResponse<?> push(@RequestAttribute("authUserId") String userId,
                               @RequestBody SyncPushRequest request) {
        PushOutcome o = syncBackupService.push(userId, request);
        return switch (o.getType()) {
            case OK -> ApiResponse.success(
                    SyncPushResultDto.of(o.getVersion(), o.isDeduped()));
            case CONFLICT -> ApiResponse.builder()
                    .code(o.isEmptyConflict() ? 40902 : 40901)
                    .message("版本冲突")
                    .data(o.getMeta())
                    .build();
            case RATED -> ApiResponse.builder()
                    .code(42901)
                    .message("上传过于频繁")
                    .data(RateLimitData.of(o.getRetryAfterSeconds()))
                    .build();
        };
    }
}
```

- 40001/40002/40003 从 service 抛出后由 `GlobalExceptionHandler` 统一转信封（data=null），controller 不 catch
- 401 由 AuthInterceptor 先行处理；`authUserId` 与 CopilotController 同法（E1/E3）

## 8. WebConfig 接线（E3，一行）

```java
registry.addInterceptor(authInterceptor).addPathPatterns(
        "/api/auth/profile/**",
        "/api/auth/logout",
        "/api/auth/recovery/confirm",
        "/api/copilot/**",
        "/api/sync/**");   // 新增：服务端密文同步（design E3，缺失则端点无鉴权裸奔）
```

native 变体注记：`app.auth.enabled` 不配置时 WebConfig/AuthInterceptor 整体不装配，sync 端点因缺 `authUserId` 必然失败——与 copilot 行为一致，属预期（无 auth 即无 sync）。

## 9. 测试

### 9.1 SyncBackupServiceTest（Mockito 单测，同 SessionServiceTest 模式）

仓库全部 mock（`dataRepository` / `historyRepository`）；ObjectMapper 用真实实例（tools.jackson `new ObjectMapper()`，仅测试）。用例表：

| 用例 | 期望 |
|---|---|
| 首传（base=0，云端空，回读 mock 1） | OK version=1；历史不落（current==null） |
| 正常覆盖 base=6（current.version=6） | OK version=7（回读）；insertIgnore(version=6)；prune(<2) |
| 云端空但 base=5（回读 mock 1） | **OK version=1**（E2：回读而非推算 base+1=6） |
| base=0 但云端已有（casUpsert→0） | CONFLICT emptyConflict=true → Controller 转 40902 |
| base 落后（base=5，云端 6，casUpsert→0） | CONFLICT → 40901；meta.version=回读值 |
| version==base 且 hash 同 | OK deduped=true；casUpsert/insertIgnore 均 never |
| version==base+1 且 hash 同 | OK deduped=true（丢失响应重试） |
| 信封畸形（缺 ct / v=2 / alg 错 / iv 空串 / 非 JSON） | BusinessException 40001 |
| 信封字节数 > maxEnvelopeBytes | 40002 |
| hash 缺失 / 大写 / 63 位 | 40003 |
| baseVersion null / -1 | 40001（E8） |
| payloadBytes 声明与实际不符 | 40001 |
| updated_at 距今 < 窗口（rate-limit-millis） | RATED retryAfterSeconds ≥ 1；casUpsert never |
| updated_at 距今 ≥ 窗口 | 放行走 CAS |
| insertIgnore 返回 0（唯一冲突，E7） | 主流程不受影响，prune 照常 |
| 日志断言（可选） | 不含信封内容，仅 hash 前 8 位 |

### 9.2 L1 回归集成测试（SyncBackupL1IntegrationTest，真 PostgreSQL；勿用 @DataJpaTest/H2——ON CONFLICT 方言行为与 PG 有差异，用例价值就在与生产同语义）

**验收必选项**：真 PG（Testcontainers 或本地 PG）跑真实 JPA 栈，`selectVersion` 不 mock——若被改回同事务 `findById`，持久化上下文会返回 CAS 前旧实体，覆盖路径必现「下一推必 409」乒乓，用例 1 即失败。

落地形态：`@SpringBootTest + @AutoConfigureMockMvc`；`@MockitoBean SessionService` 仅伪造认证（token → 会话一一对应），拦截器/仓库/JPA/HTTP 全真实；`@TestPropertySource(properties = "sync.push.rate-limit-millis=1")` 收窄频控窗口支持连续推送（D10 默认 5s 在 §9.1 单测覆盖）；4 个随机 UUID 用户，`@BeforeEach`/`@AfterEach` 清理。用例清单（对应验收标准 L1 回归）：

| # | 场景 | 断言 |
|---|---|---|
| 1 | 覆盖路径：PUT base=6 成功 → 紧接 PUT base=7 | 响应 version=7（非 6）；再推 version=8，无「下一推必 409」乒乓 |
| 2 | 云端空但 base=5 | 响应 version=1（非 6，E2 回读） |
| 3 | 并发首传（两线程 base=0，直调真实事务栈） | 一胜一 40902 |
| 4 | 40901 的 data.version | 与 DB 实际一致（selectVersion 通道） |
| 5 | 历史恰 5 份 | 覆盖推进后 user_sync_history 恰为 {N-5..N-1}，E10 裁剪实证 |
| 6 | 历史唯一冲突吸收（E7） | 同版本历史重复 INSERT 被静默吸收，主流程不受影响 |

以下 psql 字面量 SQL 保留为手工冒烟辅助（快速验证 CAS 语句本身）：

```sql
-- ① 首传（base=0）→ 期望 INSERT 0 1
INSERT INTO user_sync_data (user_id, encrypted_payload, version, payload_hash, payload_bytes)
VALUES ('11111111-1111-1111-1111-111111111111', '{"v":1}', 1, 'aa', 8)
ON CONFLICT (user_id) DO UPDATE SET
    encrypted_payload = EXCLUDED.encrypted_payload, version = user_sync_data.version + 1,
    payload_hash = EXCLUDED.payload_hash, payload_bytes = EXCLUDED.payload_bytes, updated_at = NOW()
WHERE user_sync_data.version = 0;

-- ② 覆盖（base=1）→ 期望 INSERT 0 1（version 变 2）
--（同语句，VALUES 换 payload，WHERE 改 = 1）

-- ③ base 落后（base=1，现库 2）→ 期望 INSERT 0 0（冲突）
--（同语句，WHERE 改 = 1）

-- ④ 历史唯一冲突吸收（E7）→ 同一 INSERT … ON CONFLICT DO NOTHING 执行两遍，第二遍 INSERT 0 0

-- 清理：DELETE FROM user_sync_history WHERE user_id = '11111111-…'; DELETE FROM user_sync_data WHERE user_id = '11111111-…';
```

### 9.3 Modulith

`ModulithVerifyTest` 自动覆盖新 sync 包；验证 sync 未 import 任何其他域类型。

## 10. 里程碑与验证

| 里程碑 | 内容 | 完成标准 |
|---|---|---|
| M-B1 | schema.sql 增量 + 领域包骨架（entity/repository/dto）+ WebConfig | `./mvnw compile -q` 零错误 |
| M-B2 | Service/Controller 全量 + SyncBackupServiceTest | `./mvnw test '-Dtest=!TaskServiceTest' '-DfailIfNoTests=false'` 全绿（含 Modulith） |
| M-B3 | §9.2 真库冒烟 + 前端联调 | 冒烟 4 步全过；前端仓库 M5 验收清单通过 |

验证命令：

```sh
./mvnw compile -q
POSTGRES_PASS=… ./mvnw install '-Dtest=!TaskServiceTest' '-DfailIfNoTests=false'
```

## 11. 边界情况自测清单（后端侧）

| # | 场景 | 期望 |
|---|---|---|
| 1 | 未带 Bearer 访问任意端点 | 401 + 拦截器信封（唯一非 200） |
| 2 | recovery 受限会话访问 | 401（拦截器 scope 检查，无新增逻辑） |
| 3 | 请求体伪造 userId 字段 | 忽略：userId 只取 authUserId |
| 4 | 请求体超大（>2MB envelope） | 40002（内存级防线）；传输级由反代 client_max_body_size 扣（E6） |
| 5 | envelope 非法 JSON / 结构缺失 | 40001 |
| 6 | 并发首传（真库） | 一胜一 40902，无竞态窗口 |
| 7 | 推送成功但响应丢失后重试 | deduped=true，零成本 |
| 8 | 服务端整库回滚后同版本重推 | 历史 ON CONFLICT 吸收，主流程成功（E7） |
| 9 | 频控窗口内 409 合并重推 | 42901 + retryAfterSeconds（E9，前端按建议重试） |
| 10 | native 变体（app.auth.enabled 关闭） | 端点失败（缺 authUserId）——预期不可用，与 copilot 一致 |
| 11 | 多设备并发覆盖 | CAS 保证一胜一败，不交错丢写 |

## 12. 对前端文档的修订反馈清单（跨仓库，交前端仓库维护者同步修订）

| # | 位置 | 修订 |
|---|---|---|
| 1 | spec §5.1/§5.2、implementation §2/§3.1/§3.4 | `user_id BIGINT` → `varchar(64)`；`Long userId` → `String userId`（E1，本文档为准） |
| 2 | spec §6.1-§6.3 示例、implementation §5.2 pushBackup 映射 | 成功 `code: 0` → `code: 200`；错误码映射 0→ok 改 200→ok（E5） |
| 3 | spec §7.2、implementation §3.3 | 「云端为空但 base>0 按首传成功处理」补注：响应 version=1（非 base+1）；删除 `newVersion = baseVersion + 1` 推算行，改为服务端回读（E2） |
| 4 | implementation §3.4 `currentUserId()` placeholder | 定案：`@RequestAttribute("authUserId") String userId`，无需 import auth 任何类型（E1/E3） |
| 5 | implementation §3.3 落地前核对项 | 定案：BusinessException 不扩展 data；Service 返回 PushOutcome，Controller 组装带 data 响应（E4） |
| 6 | spec §10 部署前提 | 补：反代 location 级 `client_max_body_size ≥ 3m`（E6） |
| 7 | spec §6.3/§4.2 | 补定义：payloadHash 必填（40003）、baseVersion 必填且 ≥0（40001）（E8） |
| 8 | implementation §1.1 文件清单 | 后端部分以本文档 §1 为准（新增 PushOutcome/RateLimitData、WebConfig 接线、历史 insert-ignore） |
| 9 | spec §7.2、implementation §3.3 历史裁剪规则 | `DELETE version <= newVersion - 5` 是 off-by-one（`<=` 连 N-5 一并删，只能留 4 份），改为 `DELETE version < newVersion - 5`，保留 {N-5..N-1} 恰 5 份（E10） |
| 10 | spec/implementation 频控「5s」表述 | 频控窗口参数化 `sync.push.rate-limit-millis`（defaultValue 5000 写死代码，不进 yml）；生产语义不变，前端文档如引用「写死 5s」需同步措辞（D10/D11） |
