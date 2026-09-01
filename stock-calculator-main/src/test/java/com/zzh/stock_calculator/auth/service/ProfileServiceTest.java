package com.zzh.stock_calculator.auth.service;
import com.zzh.stock_calculator.common.BusinessException;
import com.zzh.stock_calculator.common.ProfileConflictException;
import com.zzh.stock_calculator.auth.dto.ProfileResponse;
import com.zzh.stock_calculator.auth.dto.ProfileUpsertRequest;
import com.zzh.stock_calculator.auth.entity.UserProfileEntity;
import com.zzh.stock_calculator.auth.repository.UserProfileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ProfileService 单元测试（实行方案 B3 §4.3）：缺行 404、首建无条件、If-Match 乐观锁、Base64 校验。
 */
@ExtendWith(MockitoExtension.class)
class ProfileServiceTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_OFFSET_DATE_TIME;
    /** UTC 基准：与 ProfileService 的 canonicalVersion（统一转 UTC）输出一致 */
    private static final OffsetDateTime UPDATED_AT = OffsetDateTime.now(ZoneOffset.UTC);

    @Mock private UserProfileRepository userProfileRepository;

    private ProfileService profileService;

    @BeforeEach
    void setUp() {
        profileService = new ProfileService(userProfileRepository);
    }

    private UserProfileEntity existingProfile() {
        UserProfileEntity entity = UserProfileEntity.builder()
                .id(USER_ID)
                .passwordPayload("old-password-payload")
                .passwordIv("MTIzNDU2Nzg5MDEy")
                .recoveryPayload("old-recovery-payload")
                .recoveryIv("MTIzNDU2Nzg5MDEy")
                .build();
        // @UpdateTimestamp 不在 mock 环境生效，手动补 updatedAt（If-Match 版本基准）
        entity.setUpdatedAt(UPDATED_AT);
        return entity;
    }

    private ProfileUpsertRequest request() {
        ProfileUpsertRequest req = new ProfileUpsertRequest();
        req.setPasswordPayload("Y2lwaGVydGV4dC1wYXlsb2Fk");
        req.setPasswordIv("MTIzNDU2Nzg5MDEy");
        req.setRecoveryPayload("cmVjb3ZlcnktcGF5bG9hZA==");
        req.setRecoveryIv("MTIzNDU2Nzg5MDEy");
        return req;
    }

    @Test
    void getMissingReturns404() {
        when(userProfileRepository.findById(USER_ID)).thenReturn(Optional.empty());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> profileService.get(USER_ID));

        assertEquals(404, ex.getCode());
    }

    @Test
    void getReturnsCiphertextWithIsoUpdatedAt() {
        when(userProfileRepository.findById(USER_ID)).thenReturn(Optional.of(existingProfile()));

        ProfileResponse resp = profileService.get(USER_ID);

        assertEquals("old-password-payload", resp.getPasswordPayload());
        assertEquals("old-recovery-payload", resp.getRecoveryPayload());
        assertEquals(ISO.format(UPDATED_AT), resp.getUpdatedAt());
    }

    @Test
    void upsertCreatesUnconditionallyWhenAbsent() {
        when(userProfileRepository.findById(USER_ID)).thenReturn(Optional.empty());
        when(userProfileRepository.saveAndFlush(any(UserProfileEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        // 缺行是合法中间态，首建不校验 If-Match（两设备竞态由唯一主键兜底）
        ProfileResponse resp = profileService.upsert(USER_ID, request(), null);

        ArgumentCaptor<UserProfileEntity> captor = ArgumentCaptor.forClass(UserProfileEntity.class);
        verify(userProfileRepository).saveAndFlush(captor.capture());
        assertEquals(USER_ID, captor.getValue().getId());
        assertEquals("Y2lwaGVydGV4dC1wYXlsb2Fk", captor.getValue().getPasswordPayload());
        assertEquals("cmVjb3ZlcnktcGF5bG9hZA==", captor.getValue().getRecoveryPayload());
        assertNull(resp.getUpdatedAt(), "mock 环境无 @UpdateTimestamp，新建行 updatedAt 为 null");
    }

    @Test
    void upsertUpdatesWhenIfMatchEqualsCurrentVersion() {
        when(userProfileRepository.findById(USER_ID)).thenReturn(Optional.of(existingProfile()));
        when(userProfileRepository.saveAndFlush(any(UserProfileEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        ProfileResponse resp = profileService.upsert(USER_ID, request(), ISO.format(UPDATED_AT));

        ArgumentCaptor<UserProfileEntity> captor = ArgumentCaptor.forClass(UserProfileEntity.class);
        verify(userProfileRepository).saveAndFlush(captor.capture());
        assertEquals("Y2lwaGVydGV4dC1wYXlsb2Fk", captor.getValue().getPasswordPayload());
        assertEquals("cmVjb3ZlcnktcGF5bG9hZA==", captor.getValue().getRecoveryPayload());
        assertEquals(ISO.format(UPDATED_AT), resp.getUpdatedAt());
    }

    @Test
    void upsertConflictWhenIfMatchMissing() {
        when(userProfileRepository.findById(USER_ID)).thenReturn(Optional.of(existingProfile()));

        ProfileConflictException ex = assertThrows(ProfileConflictException.class,
                () -> profileService.upsert(USER_ID, request(), null));

        assertEquals(ISO.format(UPDATED_AT), ex.getServerUpdatedAt());
        verify(userProfileRepository, never()).saveAndFlush(any());
    }

    @Test
    void upsertConflictWhenIfMatchStale() {
        when(userProfileRepository.findById(USER_ID)).thenReturn(Optional.of(existingProfile()));

        ProfileConflictException ex = assertThrows(ProfileConflictException.class,
                () -> profileService.upsert(USER_ID, request(), ISO.format(UPDATED_AT.minusMinutes(5))));

        assertEquals(ISO.format(UPDATED_AT), ex.getServerUpdatedAt());
        verify(userProfileRepository, never()).saveAndFlush(any());
    }

    @Test
    void upsertRejectsNonBase64Payload() {
        ProfileUpsertRequest req = request();
        req.setPasswordPayload("not-base64!!");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> profileService.upsert(USER_ID, req, null));

        assertEquals(400, ex.getCode());
        verify(userProfileRepository, never()).saveAndFlush(any());
    }

    @Test
    void upsertRejectsBlankField() {
        ProfileUpsertRequest req = request();
        req.setRecoveryIv(" ");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> profileService.upsert(USER_ID, req, null));

        assertEquals(400, ex.getCode());
        verify(userProfileRepository, never()).saveAndFlush(any());
    }
}
