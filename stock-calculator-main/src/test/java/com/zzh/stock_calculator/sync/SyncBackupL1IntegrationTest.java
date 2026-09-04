package com.zzh.stock_calculator.sync;

import com.zzh.stock_calculator.auth.entity.AuthSessionEntity;
import com.zzh.stock_calculator.auth.service.SessionService;
import com.zzh.stock_calculator.sync.dto.SyncDtos.PushOutcome;
import com.zzh.stock_calculator.sync.dto.SyncDtos.SyncPushRequest;
import com.zzh.stock_calculator.sync.service.SyncBackupService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * L1 回归用例（验收必选项，docs/server-sync-backend-implementation.md §9.2）：
 * 真 PostgreSQL + 真实 JPA 栈，selectVersion 不 mock——验证 CAS 成功后 native 标量回读
 * 绕开持久化上下文（若被改回 findById，覆盖路径返回 CAS 前旧版本，用例 1 即失败）。
 * SessionService 仅伪造认证（token → 会话），仓库/JPA/HTTP 全真实。
 * 频控窗口经属性收窄为 1ms（D10 默认 5s 在单测 §9.1 覆盖），连续推送不触发 42901。
 * 禁用 H2：ON CONFLICT … WHERE / DO NOTHING 是 PG 方言语义，用例价值就在与生产同语义。
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "sync.push.rate-limit-millis=1")
class SyncBackupL1IntegrationTest {

    /** 每类运行唯一，避免跨运行数据串扰；用后即清 */
    private static final String U1 = UUID.randomUUID().toString();
    private static final String U2 = UUID.randomUUID().toString();
    private static final String U3 = UUID.randomUUID().toString();
    private static final String U4 = UUID.randomUUID().toString();
    private static final List<String> ALL_USERS = List.of(U1, U2, U3, U4);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SyncBackupService service;      // 并发用例直调（真实 JPA 事务栈）

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private SessionService sessionService;  // 仅伪造认证；拦截器/仓库/JPA 全真实

    @BeforeEach
    void setUp() {
        createTablesIfAbsent();
        cleanup();
        // token 与 userId 一一对应（tokenOf），伪造会话但拦截器/仓库/JPA 全真实
        for (String u : ALL_USERS) {
            when(sessionService.resolve(tokenOf(u))).thenReturn(AuthSessionEntity.builder()
                    .id(UUID.randomUUID())
                    .userId(UUID.fromString(u))
                    .tokenHash("test-hash")
                    .scope("full")
                    .build());
        }
    }

    @AfterEach
    void tearDown() {
        cleanup();
    }

    private void createTablesIfAbsent() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS user_sync_data (
                    user_id           varchar(64) NOT NULL,
                    encrypted_payload text NOT NULL,
                    version           int8 NOT NULL,
                    payload_hash      varchar(64) NULL,
                    payload_bytes     int4 NOT NULL,
                    created_at        timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
                    updated_at        timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
                    CONSTRAINT user_sync_data_pkey PRIMARY KEY (user_id)
                )""");
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS user_sync_history (
                    id                bigserial NOT NULL,
                    user_id           varchar(64) NOT NULL,
                    version           int8 NOT NULL,
                    encrypted_payload text NOT NULL,
                    payload_bytes     int4 NOT NULL,
                    created_at        timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
                    CONSTRAINT user_sync_history_pkey PRIMARY KEY (id),
                    CONSTRAINT uq_user_sync_history UNIQUE (user_id, version)
                )""");
    }

    private void cleanup() {
        for (String u : ALL_USERS) {
            jdbcTemplate.update("DELETE FROM user_sync_history WHERE user_id = ?", u);
            jdbcTemplate.update("DELETE FROM user_sync_data WHERE user_id = ?", u);
        }
    }

    // ==================== 信封与请求构造 ====================

    private record Envelope(String json, String hash, int bytes) {
    }

    private static Envelope envelope(int i) {
        String json = "{\"v\":1,\"alg\":\"A256GCM\",\"iv\":\"AAAAAAAAAAAAAAAAAAAAAA==\",\"ct\":\""
                + java.util.Base64.getEncoder()
                        .encodeToString(("payload-" + i).getBytes(StandardCharsets.UTF_8)) + "\"}";
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(json.getBytes(StandardCharsets.UTF_8));
            return new Envelope(json, HexFormat.of().formatHex(digest), json.getBytes(StandardCharsets.UTF_8).length);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String pushBody(long baseVersion, Envelope e) {
        return objectMapper.writeValueAsString(Map.of(
                "baseVersion", baseVersion,
                "envelope", e.json(),
                "payloadHash", e.hash(),
                "payloadBytes", e.bytes()));
    }

    private String tokenOf(String userId) {
        return "tok-" + userId;
    }

    private ResultActions httpPush(String userId, long baseVersion, Envelope e) throws Exception {
        return mockMvc.perform(put("/api/sync/backup")
                .header("Authorization", "Bearer " + tokenOf(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(pushBody(baseVersion, e)));
    }

    private void seedTo(String userId, long targetVersion) {
        for (long v = 1; v <= targetVersion; v++) {
            PushOutcome o = service.push(userId, requestFor(v, v - 1));
            assertEquals(PushOutcome.OutcomeType.OK, o.getType(), "seed v" + v);
        }
    }

    private SyncPushRequest requestFor(long v, long base) {
        Envelope e = envelope((int) v);
        return SyncPushRequest.builder()
                .baseVersion(base).envelope(e.json()).payloadHash(e.hash()).payloadBytes(e.bytes())
                .build();
    }

    // ==================== 用例 1：覆盖路径无乒乓（L1 回归核心） ====================

    @Test
    void overwritePathNoPingPong() throws Exception {
        seedTo(U1, 6);
        // PUT base=6 → 断言响应 version=7（若回读被改成 findById，此处返回 6，用例即失败）
        httpPush(U1, 6, envelope(7))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.version").value(7))
                .andExpect(jsonPath("$.data.deduped").value(false));
        // 紧接 PUT base=7 成功 → version=8（无乒乓）
        httpPush(U1, 7, envelope(8))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(8));
    }

    // ==================== 用例 2：云端空但 base=5 → version=1（非 6） ====================

    @Test
    void emptyCloudButBase5ReturnsVersion1() throws Exception {
        httpPush(U2, 5, envelope(5))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.version").value(1));
    }

    // ==================== 用例 3：并发首传，一胜一 40902 ====================

    @Test
    void concurrentFirstPushOneWinnerOne40902() throws Exception {
        Envelope a = envelope(1), b = envelope(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<PushOutcome>> futures = pool.invokeAll(List.of(
                    (Callable<PushOutcome>) () -> service.push(U3,
                            SyncPushRequest.builder().baseVersion(0L)
                                    .envelope(a.json()).payloadHash(a.hash()).payloadBytes(a.bytes()).build()),
                    (Callable<PushOutcome>) () -> service.push(U3,
                            SyncPushRequest.builder().baseVersion(0L)
                                    .envelope(b.json()).payloadHash(b.hash()).payloadBytes(b.bytes()).build())));
            List<PushOutcome> outcomes = new ArrayList<>();
            for (Future<PushOutcome> f : futures) {
                outcomes.add(f.get());
            }
            long okCount = outcomes.stream().filter(o -> o.getType() == PushOutcome.OutcomeType.OK).count();
            long conflictCount = outcomes.stream().filter(o -> o.getType() == PushOutcome.OutcomeType.CONFLICT).count();
            assertEquals(1, okCount);
            assertEquals(1, conflictCount);
            assertEquals(1L, outcomes.stream().filter(o -> o.getType() == PushOutcome.OutcomeType.OK)
                    .findFirst().orElseThrow().getVersion());
            assertTrue(outcomes.stream().filter(o -> o.getType() == PushOutcome.OutcomeType.CONFLICT)
                    .findFirst().orElseThrow().isEmptyConflict());
        } finally {
            pool.shutdownNow();
        }
    }

    // ==================== 用例 4：40901 的 data.version 与 DB 实际一致 ====================

    @Test
    void conflictDataVersionMatchesDb() throws Exception {
        seedTo(U1, 8);
        httpPush(U1, 5, envelope(99))                  // 落后 base → 40901
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40901))
                .andExpect(jsonPath("$.data.hasData").value(true))
                .andExpect(jsonPath("$.data.version").value(8));
        Long dbVersion = jdbcTemplate.queryForObject(
                "SELECT version FROM user_sync_data WHERE user_id = ?", Long.class, U1);
        assertEquals(8L, dbVersion);                   // 响应与 DB 实际一致（selectVersion 通道）
    }

    // ==================== 用例 5：历史恰 5 份（D8） ====================

    @Test
    void historyKeepsExactlyFive() {
        seedTo(U4, 8);
        List<Long> versions = jdbcTemplate.queryForList(
                "SELECT version FROM user_sync_history WHERE user_id = ? ORDER BY version", Long.class, U4);
        assertEquals(List.of(3L, 4L, 5L, 6L, 7L), versions);
    }

    // ==================== 用例 6：历史唯一冲突被 ON CONFLICT 吸收（E7） ====================

    @Test
    void historyUniqueConflictAbsorbed() {
        seedTo(U4, 8);
        // 预插 (user,8) 历史行，模拟整库回滚残留；随后覆盖路径 insertIgnore 撞唯一约束 → 吸收
        jdbcTemplate.update("""
                INSERT INTO user_sync_history (user_id, version, encrypted_payload, payload_bytes)
                VALUES (?, 8, '{"v":1}', 8)
                """, U4);
        PushOutcome o = service.push(U4, requestFor(9, 8));
        assertEquals(PushOutcome.OutcomeType.OK, o.getType());
        assertEquals(9L, o.getVersion());
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM user_sync_history WHERE user_id = ? AND version = 8", Integer.class, U4);
        assertEquals(1, count);                        // 恰一行：被吸收而非报错/重复
    }

    // ==================== 用例 7：无 token /meta → 401（拦截器接线 E3） ====================

    @Test
    void metaWithoutTokenRejected401() throws Exception {
        mockMvc.perform(get("/api/sync/backup/meta"))
                .andExpect(status().isUnauthorized());
    }
}
