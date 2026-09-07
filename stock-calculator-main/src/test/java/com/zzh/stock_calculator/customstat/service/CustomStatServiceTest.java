package com.zzh.stock_calculator.customstat.service;

import com.zzh.stock_calculator.common.BusinessException;
import com.zzh.stock_calculator.customstat.dto.CustomStatDtos.CustomStatListResponse;
import com.zzh.stock_calculator.customstat.entity.UserCustomStat;
import com.zzh.stock_calculator.customstat.repository.UserCustomStatRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CustomStatService 单元测试（纯 JUnit + Mockito，无 Spring 上下文 / 无 DB）。
 * 契约：docs/custom-stats-server-sync.md —— 校验违规统一 40001、payload 原样存取、
 * 行数兜底 200、DELETE 幂等、GET 空库返回空数组。
 */
@ExtendWith(MockitoExtension.class)
class CustomStatServiceTest {

    private static final String USER_ID = "0b8f6c1e-0000-4000-8000-000000000001";
    private static final String DEF_ID = "3f2a9c1e-1111-4000-8000-0000000000aa";

    @Mock
    private UserCustomStatRepository repository;

    private final ObjectMapper objectMapper = new ObjectMapper();   // tools.jackson 真实例
    private CustomStatService service;

    @BeforeEach
    void setUp() {
        service = new CustomStatService(repository, objectMapper);
    }

    /** 合法定义 payload（与 D17 §3 DTO 一致；额外含 lastResult 等透传字段） */
    private static String validPayload(String defId) {
        return "{\"id\":\"" + defId + "\",\"name\":\"各股做T收益排行\",\"description\":\"已平仓轮次净收益\","
                + "\"prompt\":\"统计各股做T净收益\",\"code\":\"(ctx) => { return ctx.helpers.round2(1); }\","
                + "\"schemaVersion\":1,\"kind\":\"chart\",\"lastResult\":{\"kind\":\"chart\",\"data\":[]},"
                + "\"favorite\":false,\"pinned\":true,\"pinnedAt\":\"2026-09-07T10:00:00+08:00\","
                + "\"runCount\":3,\"createdAt\":\"2026-09-07T09:00:00+08:00\","
                + "\"updatedAt\":\"2026-09-07T12:00:00+08:00\"}";
    }

    private void stubInsertAllowed(String defId) {
        when(repository.existsByUserIdAndDefId(USER_ID, defId)).thenReturn(false);
        when(repository.countByUserId(USER_ID)).thenReturn(0L);
    }

    // ==================== GET：列表与空库 ====================

    @Test
    void list_emptyRepo_returnsEmptyListNot404() {
        when(repository.findByUserIdOrderByUpdatedAtClientDesc(USER_ID)).thenReturn(List.of());

        CustomStatListResponse resp = service.list(USER_ID);

        assertTrue(resp.getList().isEmpty());
    }

    @Test
    void list_payloadsParsedVerbatim() {
        String p1 = validPayload(DEF_ID);
        String p2 = "{\"id\":\"other\",\"name\":\"n\",\"code\":\"(ctx)=>1\",\"schemaVersion\":1,"
                + "\"updatedAt\":\"2026-09-06T08:00:00Z\"}";
        when(repository.findByUserIdOrderByUpdatedAtClientDesc(USER_ID)).thenReturn(List.of(
                row(1L, p1), row(2L, p2)));

        CustomStatListResponse resp = service.list(USER_ID);

        assertEquals(2, resp.getList().size());
        var node1 = objectMapper.readTree(objectMapper.writeValueAsString(resp.getList().get(0)));
        // 原样回传：字段逐项在（含服务端不理解的 lastResult/pinnedAt 等透传字段）
        assertEquals("各股做T收益排行", node1.get("name").asString());
        assertEquals(1, node1.get("schemaVersion").asInt());
        assertEquals("2026-09-07T12:00:00+08:00", node1.get("updatedAt").asString());
        assertEquals("chart", node1.get("lastResult").get("kind").asString());
        assertEquals("2026-09-07T10:00:00+08:00", node1.get("pinnedAt").asString());
    }

    @Test
    void list_corruptStoredPayload_defensiveRawStringNotBlowUp() {
        // 理论不可达（入库前已验 JSON）；防御分支不得炸整个列表
        when(repository.findByUserIdOrderByUpdatedAtClientDesc(USER_ID)).thenReturn(List.of(
                row(1L, "not-json"), row(2L, validPayload(DEF_ID))));

        CustomStatListResponse resp = service.list(USER_ID);

        assertEquals(2, resp.getList().size());
        assertInstanceOf(String.class, resp.getList().get(0));
    }

    // ==================== PUT：成功路径（原样存取） ====================

    @Test
    void upsert_valid_storesPayloadVerbatimWithClientUpdatedAt() {
        String payload = validPayload(DEF_ID);
        stubInsertAllowed(DEF_ID);

        service.upsert(USER_ID, DEF_ID, payload);

        ArgumentCaptor<String> raw = ArgumentCaptor.forClass(String.class);
        verify(repository).upsertPayload(eq(USER_ID), eq(DEF_ID), raw.capture(),
                eq("2026-09-07T12:00:00+08:00"));
        // 原样存储：入参 JSON 文本逐字节落库（服务端不改写、不重排、不裁剪）
        assertEquals(payload, raw.getValue());
    }

    @Test
    void upsert_existingRow_skipsRowCountCheck() {
        when(repository.existsByUserIdAndDefId(USER_ID, DEF_ID)).thenReturn(true);

        service.upsert(USER_ID, DEF_ID, validPayload(DEF_ID));

        verify(repository, never()).countByUserId(anyString());
        verify(repository).upsertPayload(eq(USER_ID), eq(DEF_ID), anyString(), anyString());
    }

    // ==================== PUT：40001 校验矩阵 ====================

    private BusinessException assertInvalid(String defId, String payload) {
        BusinessException e = assertThrows(BusinessException.class,
                () -> service.upsert(USER_ID, defId, payload));
        assertEquals(40001, e.getCode());
        verify(repository, never()).upsertPayload(anyString(), anyString(), anyString(), anyString());
        return e;
    }

    @Test
    void upsert_defIdMismatch_throws40001() {
        assertInvalid(DEF_ID, validPayload("another-id"));
    }

    @Test
    void upsert_defIdBlankOrOversized_throws40001() {
        assertInvalid("  ", validPayload(DEF_ID));
        assertInvalid("x".repeat(65), validPayload("x".repeat(65)));
        assertInvalid(null, validPayload(DEF_ID));
    }

    @Test
    void upsert_payloadMissingOrBlankOrInvalidJson_throws40001() {
        assertInvalid(DEF_ID, null);
        assertInvalid(DEF_ID, "   ");
        assertInvalid(DEF_ID, "{not-json");
        assertInvalid(DEF_ID, "[1,2]");
    }

    @Test
    void upsert_payloadOver32KB_throws40001() {
        String big = "{\"id\":\"" + DEF_ID + "\",\"name\":\"n\",\"code\":\"(ctx)=>1\",\"schemaVersion\":1,"
                + "\"updatedAt\":\"2026-09-07T12:00:00Z\",\"pad\":\"" + "x".repeat(32 * 1024) + "\"}";
        assertInvalid(DEF_ID, big);
    }

    @Test
    void upsert_missingRequiredFields_throws40001() {
        assertInvalid(DEF_ID, "{\"code\":\"(ctx)=>1\",\"schemaVersion\":1,\"updatedAt\":\"t\"}");            // 缺 name
        assertInvalid(DEF_ID, "{\"name\":\"n\",\"schemaVersion\":1,\"updatedAt\":\"t\"}");                   // 缺 code
        assertInvalid(DEF_ID, "{\"name\":\"n\",\"code\":\"(ctx)=>1\",\"updatedAt\":\"t\"}");                 // 缺 schemaVersion
        assertInvalid(DEF_ID, "{\"name\":\"n\",\"code\":\"(ctx)=>1\",\"schemaVersion\":1}");                 // 缺 updatedAt
        assertInvalid(DEF_ID, "{\"name\":1,\"code\":\"(ctx)=>1\",\"schemaVersion\":1,\"updatedAt\":\"t\"}"); // name 非字符串
    }

    @Test
    void upsert_fieldLimits_throws40001() {
        String head = "{\"id\":\"" + DEF_ID + "\",\"code\":\"(ctx)=>1\",\"schemaVersion\":1,"
                + "\"updatedAt\":\"2026-09-07T12:00:00Z\",";
        assertInvalid(DEF_ID, head + "\"name\":\"" + "名".repeat(41) + "\"}");                       // name >40 字符
        assertInvalid(DEF_ID, head + "\"name\":\"n\",\"description\":\"" + "述".repeat(201) + "\"}"); // description >200 字符
        assertInvalid(DEF_ID, head + "\"name\":\"n\",\"prompt\":\"" + "x".repeat(2 * 1024 + 1) + "\"}"); // prompt >2KB 字节
        String head2 = "{\"id\":\"" + DEF_ID + "\",\"name\":\"n\",\"schemaVersion\":1,"
                + "\"updatedAt\":\"2026-09-07T12:00:00Z\",";
        assertInvalid(DEF_ID, head2 + "\"code\":\"(ctx)=>//" + "x".repeat(16 * 1024) + "\"}");       // code >16KB 字节
    }

    @Test
    void upsert_updatedAtOver40Chars_throws40001() {
        String payload = "{\"id\":\"" + DEF_ID + "\",\"name\":\"n\",\"code\":\"(ctx)=>1\",\"schemaVersion\":1,"
                + "\"updatedAt\":\"" + "2026-09-07T12:00:00.123456789123456789123456+08:00" + "\"}"; // >40 字符
        assertInvalid(DEF_ID, payload);
    }

    @Test
    void upsert_forwardCompatFieldsAccepted() {
        // 未知字段 + schemaVersion=2（更高版本产生）：服务端原样存取，前端自行跳过（D17 §5）
        String payload = "{\"id\":\"" + DEF_ID + "\",\"name\":\"n\",\"code\":\"(ctx)=>1\",\"schemaVersion\":2,"
                + "\"updatedAt\":\"2026-09-07T12:00:00Z\",\"unknownFutureField\":{\"a\":1}}";
        stubInsertAllowed(DEF_ID);

        service.upsert(USER_ID, DEF_ID, payload);

        verify(repository).upsertPayload(USER_ID, DEF_ID, payload, "2026-09-07T12:00:00Z");
    }

    // ==================== PUT：行数兜底 ====================

    @Test
    void upsert_newRowAtLimit_throws40001() {
        when(repository.existsByUserIdAndDefId(USER_ID, DEF_ID)).thenReturn(false);
        when(repository.countByUserId(USER_ID)).thenReturn(200L);

        assertInvalid(DEF_ID, validPayload(DEF_ID));
    }

    @Test
    void upsert_newRowAt199_ok() {
        when(repository.existsByUserIdAndDefId(USER_ID, DEF_ID)).thenReturn(false);
        when(repository.countByUserId(USER_ID)).thenReturn(199L);

        service.upsert(USER_ID, DEF_ID, validPayload(DEF_ID));

        verify(repository).upsertPayload(eq(USER_ID), eq(DEF_ID), anyString(), anyString());
    }

    // ==================== DELETE：幂等 ====================

    @Test
    void delete_physicallyDeletes_alwaysOk() {
        when(repository.deleteByUserIdAndDefId(USER_ID, DEF_ID)).thenReturn(1);
        service.delete(USER_ID, DEF_ID);
        verify(repository).deleteByUserIdAndDefId(USER_ID, DEF_ID);
    }

    @Test
    void delete_missingRow_stillOk_zeroAffected() {
        when(repository.deleteByUserIdAndDefId(USER_ID, "ghost")).thenReturn(0);
        service.delete(USER_ID, "ghost");   // 不抛错 = 200（幂等语义）
        verify(repository).deleteByUserIdAndDefId(USER_ID, "ghost");
    }

    // ==================== 辅助 ====================

    private UserCustomStat row(long id, String payload) {
        return UserCustomStat.builder()
                .id(id)
                .userId(USER_ID)
                .defId(DEF_ID)
                .payload(payload)
                .updatedAtClient("2026-09-07T12:00:00+08:00")
                .build();
    }
}
