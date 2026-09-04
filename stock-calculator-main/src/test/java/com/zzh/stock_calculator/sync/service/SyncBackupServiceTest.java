package com.zzh.stock_calculator.sync.service;

import com.zzh.stock_calculator.common.BusinessException;
import com.zzh.stock_calculator.sync.dto.SyncDtos.PushOutcome;
import com.zzh.stock_calculator.sync.dto.SyncDtos.SyncPushRequest;
import com.zzh.stock_calculator.sync.entity.UserSyncData;
import com.zzh.stock_calculator.sync.repository.UserSyncDataRepository;
import com.zzh.stock_calculator.sync.repository.UserSyncHistoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SyncBackupService 单元测试（docs/server-sync-backend-implementation.md §9.1）：
 * 校验全分支 / 去重两分支 / 频控 / 冲突映射 / 历史落库与裁剪 / 版本回读（E2）。
 * 仓库全部 mock；CAS SQL 与 L1 回归由 SyncBackupL1IntegrationTest（真 PG）承担。
 */
@ExtendWith(MockitoExtension.class)
class SyncBackupServiceTest {

    private static final String USER = "11111111-1111-1111-1111-111111111111";
    private static final String HASH_A = "a".repeat(64);
    private static final String HASH_B = "b".repeat(64);

    @Mock
    private UserSyncDataRepository dataRepository;

    @Mock
    private UserSyncHistoryRepository historyRepository;

    private SyncBackupService service;

    @BeforeEach
    void setUp() {
        service = new SyncBackupService(dataRepository, historyRepository, new ObjectMapper());
        ReflectionTestUtils.setField(service, "maxEnvelopeBytes", 2_000_000);
        ReflectionTestUtils.setField(service, "rateLimitMillis", 5_000L);
    }

    private static SyncPushRequest req(long base, String envelope, String hash, int bytes) {
        return SyncPushRequest.builder()
                .baseVersion(base).envelope(envelope).payloadHash(hash).payloadBytes(bytes)
                .build();
    }

    private static String envelopeJson(String ct) {
        return "{\"v\":1,\"alg\":\"A256GCM\",\"iv\":\"AAAAAAAAAAAAAAAAAAAAAA==\",\"ct\":\"" + ct + "\"}";
    }

    private static SyncPushRequest validReq(long base, String hash) {
        String json = envelopeJson("YQ==");                     // ct=base64("a")
        return req(base, json, hash, json.getBytes().length);
    }

    private static UserSyncData entity(long version, String hash) {
        return UserSyncData.builder()
                .userId(USER).encryptedPayload(envelopeJson("b2xk")).version(version)
                .payloadHash(hash).payloadBytes(64)
                .updatedAt(OffsetDateTime.now().minusSeconds(60))
                .build();
    }

    // ==================== 校验分支 ====================

    @Test
    void baseVersionNullRejected40001() {
        SyncPushRequest r = validReq(0, HASH_A);
        r.setBaseVersion(null);
        BusinessException e = assertThrows(BusinessException.class, () -> service.push(USER, r));
        assertEquals(40001, e.getCode());
    }

    @Test
    void baseVersionNegativeRejected40001() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> service.push(USER, validReq(-1, HASH_A)));
        assertEquals(40001, e.getCode());
    }

    @Test
    void hashMissingRejected40003() {
        SyncPushRequest r = validReq(0, HASH_A);
        r.setPayloadHash(null);
        assertEquals(40003, assertThrows(BusinessException.class,
                () -> service.push(USER, r)).getCode());
    }

    @Test
    void hashUppercaseRejected40003() {
        assertEquals(40003, assertThrows(BusinessException.class,
                () -> service.push(USER, validReq(0, HASH_A.toUpperCase()))).getCode());
    }

    @Test
    void hashTooShortRejected40003() {
        assertEquals(40003, assertThrows(BusinessException.class,
                () -> service.push(USER, validReq(0, "a".repeat(63)))).getCode());
    }

    @Test
    void oversizeEnvelopeRejected40002() {
        ReflectionTestUtils.setField(service, "maxEnvelopeBytes", 10);
        assertEquals(40002, assertThrows(BusinessException.class,
                () -> service.push(USER, validReq(0, HASH_A))).getCode());
    }

    @Test
    void malformedJsonRejected40001() {
        SyncPushRequest r = validReq(0, HASH_A);
        r.setEnvelope("not-json");
        r.setPayloadBytes(r.getEnvelope().getBytes().length);
        assertEquals(40001, assertThrows(BusinessException.class,
                () -> service.push(USER, r)).getCode());
    }

    @Test
    void structurallyBrokenEnvelopesRejected40001() {
        String[] bad = {
                "{\"v\":1,\"alg\":\"A256GCM\",\"iv\":\"AAAAAAAAAAAAAAAAAAAAAA==\"}",   // 缺 ct
                "{\"v\":2,\"alg\":\"A256GCM\",\"iv\":\"AAAAAAAAAAAAAAAAAAAAAA==\",\"ct\":\"YQ==\"}",   // v=2
                "{\"v\":1,\"alg\":\"A128GCM\",\"iv\":\"AAAAAAAAAAAAAAAAAAAAAA==\",\"ct\":\"YQ==\"}",   // alg 错
                "{\"v\":1,\"alg\":\"A256GCM\",\"iv\":\"\",\"ct\":\"YQ==\"}",           // iv 空串
                "{\"v\":1,\"alg\":\"A256GCM\",\"ct\":\"YQ==\"}"                        // 缺 iv
        };
        for (String json : bad) {
            BusinessException e = assertThrows(BusinessException.class,
                    () -> service.push(USER, req(0, json, HASH_A, json.getBytes().length)),
                    "应拒收: " + json);
            assertEquals(40001, e.getCode());
        }
    }

    @Test
    void payloadBytesMismatchRejected40001() {
        SyncPushRequest r = validReq(0, HASH_A);
        r.setPayloadBytes(r.getPayloadBytes() + 1);
        assertEquals(40001, assertThrows(BusinessException.class,
                () -> service.push(USER, r)).getCode());
    }

    // ==================== 首传 / 覆盖 / 回读（E2） ====================

    @Test
    void firstPushSucceedsWithVersion1AndNoHistory() {
        when(dataRepository.findById(USER)).thenReturn(Optional.empty());
        when(dataRepository.casUpsert(anyString(), anyString(), anyString(), anyInt(), anyLong()))
                .thenReturn(1);
        when(dataRepository.selectVersion(USER)).thenReturn(1L);

        PushOutcome o = service.push(USER, validReq(0, HASH_A));

        assertEquals(PushOutcome.OutcomeType.OK, o.getType());
        assertEquals(1L, o.getVersion());
        assertFalse(o.isDeduped());
        verify(historyRepository, never()).insertIgnore(anyString(), anyLong(), anyString(), anyInt());
        verify(historyRepository, never()).deleteByUserIdAndVersionLessThan(anyString(), anyLong());
    }

    @Test
    void overwriteBase6ReturnsReadbackVersion7WithHistory() {
        when(dataRepository.findById(USER)).thenReturn(Optional.of(entity(6, HASH_B)));
        when(dataRepository.casUpsert(anyString(), anyString(), anyString(), anyInt(), anyLong()))
                .thenReturn(1);
        when(dataRepository.selectVersion(USER)).thenReturn(7L);

        PushOutcome o = service.push(USER, validReq(6, HASH_A));

        assertEquals(PushOutcome.OutcomeType.OK, o.getType());
        assertEquals(7L, o.getVersion());
        verify(historyRepository).insertIgnore(USER, 6L, envelopeJson("b2xk"), 64);
        verify(historyRepository).deleteByUserIdAndVersionLessThan(USER, 2L);   // delete < 7-5，保留 {2..6} 恰 5 份
    }

    @Test
    void emptyCloudButBase5ReturnsActualVersion1NotBasePlusOne() {
        // E2 核心断言：INSERT 路径实际写入 v1，回读 1 而非推算的 6
        when(dataRepository.findById(USER)).thenReturn(Optional.empty());
        when(dataRepository.casUpsert(anyString(), anyString(), anyString(), anyInt(), anyLong()))
                .thenReturn(1);
        when(dataRepository.selectVersion(USER)).thenReturn(1L);

        PushOutcome o = service.push(USER, validReq(5, HASH_A));

        assertEquals(PushOutcome.OutcomeType.OK, o.getType());
        assertEquals(1L, o.getVersion());
    }

    // ==================== 冲突（PushOutcome → Controller 组装 40901/40902） ====================

    @Test
    void firstPushConflictMapsToEmptyConflict40902() {
        // 并发首传：事务内首读为空，CAS 0 行，meta 现读他人已插行
        when(dataRepository.findById(USER))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(entity(1, HASH_B)));
        when(dataRepository.casUpsert(anyString(), anyString(), anyString(), anyInt(), anyLong()))
                .thenReturn(0);

        PushOutcome o = service.push(USER, validReq(0, HASH_A));

        assertEquals(PushOutcome.OutcomeType.CONFLICT, o.getType());
        assertTrue(o.isEmptyConflict());
        assertEquals(1L, o.getMeta().getVersion());
    }

    @Test
    void staleBaseConflictCarriesReadbackMetaVersion() {
        when(dataRepository.findById(USER)).thenReturn(Optional.of(entity(6, HASH_B)));
        when(dataRepository.casUpsert(anyString(), anyString(), anyString(), anyInt(), anyLong()))
                .thenReturn(0);
        when(dataRepository.selectVersion(USER)).thenReturn(7L);

        PushOutcome o = service.push(USER, validReq(5, HASH_A));

        assertEquals(PushOutcome.OutcomeType.CONFLICT, o.getType());
        assertFalse(o.isEmptyConflict());
        assertEquals(7L, o.getMeta().getVersion());   // selectVersion 通道，非缓存旧值
    }

    // ==================== 去重（D7 两分支，先于频控） ====================

    @Test
    void dedupBranchASameVersionSameHash() {
        when(dataRepository.findById(USER)).thenReturn(Optional.of(entity(6, HASH_A)));

        PushOutcome o = service.push(USER, validReq(6, HASH_A));

        assertEquals(PushOutcome.OutcomeType.OK, o.getType());
        assertEquals(6L, o.getVersion());
        assertTrue(o.isDeduped());
        verify(dataRepository, never()).casUpsert(anyString(), anyString(), anyString(), anyInt(), anyLong());
    }

    @Test
    void dedupBranchBSameVersionPlusOneSameHash() {
        // 响应丢失重试：version==base+1 且 hash 同 → deduped 零成本
        when(dataRepository.findById(USER)).thenReturn(Optional.of(entity(7, HASH_A)));

        PushOutcome o = service.push(USER, validReq(6, HASH_A));

        assertEquals(PushOutcome.OutcomeType.OK, o.getType());
        assertEquals(7L, o.getVersion());
        assertTrue(o.isDeduped());
        verify(dataRepository, never()).casUpsert(anyString(), anyString(), anyString(), anyInt(), anyLong());
    }

    // ==================== 频控（D10）与历史（D8/E7） ====================

    @Test
    void rateLimitInsideWindowReturnsRatedWithRetryAfter() {
        UserSyncData fresh = entity(6, HASH_B);
        fresh.setUpdatedAt(OffsetDateTime.now());     // 刚写过 → 频控窗口内
        when(dataRepository.findById(USER)).thenReturn(Optional.of(fresh));

        PushOutcome o = service.push(USER, validReq(6, HASH_A));

        assertEquals(PushOutcome.OutcomeType.RATED, o.getType());
        assertTrue(o.getRetryAfterSeconds() >= 1);
        verify(dataRepository, never()).casUpsert(anyString(), anyString(), anyString(), anyInt(), anyLong());
    }

    @Test
    void historyUniqueConflictAbsorbedDoesNotBreakPush() {
        // E7：insertIgnore 返回 0（唯一冲突吸收），主流程不受影响
        when(dataRepository.findById(USER)).thenReturn(Optional.of(entity(6, HASH_B)));
        when(dataRepository.casUpsert(anyString(), anyString(), anyString(), anyInt(), anyLong()))
                .thenReturn(1);
        when(dataRepository.selectVersion(USER)).thenReturn(7L);
        when(historyRepository.insertIgnore(anyString(), anyLong(), anyString(), anyInt())).thenReturn(0);

        PushOutcome o = service.push(USER, validReq(6, HASH_A));

        assertEquals(PushOutcome.OutcomeType.OK, o.getType());
        assertEquals(7L, o.getVersion());
        verify(historyRepository).deleteByUserIdAndVersionLessThan(USER, 2L);   // delete < 7-5，保留恰 5 份
    }
}
