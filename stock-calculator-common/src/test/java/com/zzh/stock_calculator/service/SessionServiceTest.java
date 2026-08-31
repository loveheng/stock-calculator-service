package com.zzh.stock_calculator.service;

import com.zzh.stock_calculator.common.BusinessException;
import com.zzh.stock_calculator.config.AuthProperties;
import com.zzh.stock_calculator.dto.auth.AuthSessionResponse;
import com.zzh.stock_calculator.entity.AuthSessionEntity;
import com.zzh.stock_calculator.repository.AuthSessionRepository;
import com.zzh.stock_calculator.util.AuthCryptoUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SessionService 单元测试（实行方案 B3 §4.3）：签发/解析回环、吊销、续期节流、recovery scope。
 */
@ExtendWith(MockitoExtension.class)
class SessionServiceTest {

    private static final UUID USER_ID = UUID.randomUUID();

    @Mock private AuthSessionRepository authSessionRepository;

    private SessionService sessionService;

    @BeforeEach
    void setUp() {
        sessionService = new SessionService(authSessionRepository, new AuthProperties());
    }

    private AuthSessionEntity session(String tokenHash, OffsetDateTime createdAt,
                                      OffsetDateTime expiresAt, OffsetDateTime lastSeenAt) {
        AuthSessionEntity entity = AuthSessionEntity.builder()
                .id(UUID.randomUUID())
                .userId(USER_ID)
                .tokenHash(tokenHash)
                .scope(SessionService.SCOPE_FULL)
                .expiresAt(expiresAt)
                .lastSeenAt(lastSeenAt)
                .build();
        // @CreationTimestamp 不在 mock 环境生效，手动补 createdAt（resolve 计算 TTL 基准必需）
        entity.setCreatedAt(createdAt);
        return entity;
    }

    @Test
    void issueFullClampsTtlToUpperBoundAndStoresHashOnly() {
        when(authSessionRepository.save(any(AuthSessionEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        AuthSessionResponse resp = sessionService.issueFull(USER_ID, 999);

        ArgumentCaptor<AuthSessionEntity> captor = ArgumentCaptor.forClass(AuthSessionEntity.class);
        verify(authSessionRepository).save(captor.capture());
        AuthSessionEntity saved = captor.getValue();
        assertEquals(SessionService.SCOPE_FULL, saved.getScope());
        assertEquals(AuthCryptoUtil.sha256Hex(resp.getToken()), saved.getTokenHash());
        assertEquals(USER_ID, saved.getUserId());
        long days = Duration.between(OffsetDateTime.now(), saved.getExpiresAt()).toDays();
        assertTrue(days >= 29 && days <= 30, "ttl=999 应被夹取到 30 天上限");
        assertEquals(43, resp.getToken().length());
    }

    @Test
    void issueFullFloorsTtlToOneDay() {
        when(authSessionRepository.save(any(AuthSessionEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        sessionService.issueFull(USER_ID, 0);

        ArgumentCaptor<AuthSessionEntity> captor = ArgumentCaptor.forClass(AuthSessionEntity.class);
        verify(authSessionRepository).save(captor.capture());
        long hours = Duration.between(OffsetDateTime.now(), captor.getValue().getExpiresAt()).toHours();
        assertTrue(hours >= 23 && hours <= 24, "ttl=0 应被夹取到 1 天下限");
    }

    @Test
    void issueRecoveryHasShortTtlAndRecoveryScope() {
        when(authSessionRepository.save(any(AuthSessionEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        sessionService.issueRecovery(USER_ID);

        ArgumentCaptor<AuthSessionEntity> captor = ArgumentCaptor.forClass(AuthSessionEntity.class);
        verify(authSessionRepository).save(captor.capture());
        assertEquals(SessionService.SCOPE_RECOVERY, captor.getValue().getScope());
        long minutes = Duration.between(OffsetDateTime.now(), captor.getValue().getExpiresAt()).toMinutes();
        assertTrue(minutes >= 9 && minutes <= 10, "recovery 会话应为 10 分钟硬过期");
    }

    @Test
    void resolveRoundTripWithinHalfTtlDoesNotRenew() {
        String token = "unit-test-token";
        OffsetDateTime now = OffsetDateTime.now();
        AuthSessionEntity entity = session(AuthCryptoUtil.sha256Hex(token),
                now.minusHours(1), now.plus(Duration.ofDays(6)).plusHours(1), now.minusHours(1));
        when(authSessionRepository.findByTokenHash(AuthCryptoUtil.sha256Hex(token)))
                .thenReturn(Optional.of(entity));

        AuthSessionEntity resolved = sessionService.resolve(token);

        assertSame(entity, resolved);
        verify(authSessionRepository, never()).save(any());
    }

    @Test
    void resolveRenewsAfterHalfTtl() {
        String token = "unit-test-token";
        OffsetDateTime now = OffsetDateTime.now();
        // total = 9 天，last_seen 距今 5 天 > TTL/2 → 触发续期顺延至满 TTL
        AuthSessionEntity entity = session(AuthCryptoUtil.sha256Hex(token),
                now.minusDays(8), now.plusDays(1), now.minusDays(5));
        when(authSessionRepository.findByTokenHash(AuthCryptoUtil.sha256Hex(token)))
                .thenReturn(Optional.of(entity));

        sessionService.resolve(token);

        ArgumentCaptor<AuthSessionEntity> captor = ArgumentCaptor.forClass(AuthSessionEntity.class);
        verify(authSessionRepository).save(captor.capture());
        assertTrue(captor.getValue().getExpiresAt().isAfter(now.plusDays(8)), "续期后应顺延至满 TTL");
        assertTrue(captor.getValue().getLastSeenAt().isAfter(now.minusSeconds(5)));
    }

    @Test
    void resolveRejectsRevoked() {
        String token = "revoked-token";
        OffsetDateTime now = OffsetDateTime.now();
        AuthSessionEntity entity = session(AuthCryptoUtil.sha256Hex(token),
                now.minusDays(1), now.plusDays(6), now.minusDays(1));
        entity.setRevokedAt(now);
        when(authSessionRepository.findByTokenHash(AuthCryptoUtil.sha256Hex(token)))
                .thenReturn(Optional.of(entity));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> sessionService.resolve(token));

        assertEquals(401, ex.getCode());
    }

    @Test
    void resolveRejectsExpired() {
        String token = "expired-token";
        OffsetDateTime now = OffsetDateTime.now();
        AuthSessionEntity entity = session(AuthCryptoUtil.sha256Hex(token),
                now.minusDays(8), now.minusSeconds(1), now.minusDays(1));
        when(authSessionRepository.findByTokenHash(AuthCryptoUtil.sha256Hex(token)))
                .thenReturn(Optional.of(entity));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> sessionService.resolve(token));

        assertEquals(401, ex.getCode());
    }

    @Test
    void resolveRejectsUnknownToken() {
        when(authSessionRepository.findByTokenHash(AuthCryptoUtil.sha256Hex("ghost")))
                .thenReturn(Optional.empty());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> sessionService.resolve("ghost"));

        assertEquals(401, ex.getCode());
    }

    @Test
    void revokeByTokenHashMarksRevocation() {
        String tokenHash = AuthCryptoUtil.sha256Hex("tok");
        OffsetDateTime now = OffsetDateTime.now();
        AuthSessionEntity entity = session(tokenHash, now.minusDays(1), now.plusDays(6), now.minusDays(1));
        when(authSessionRepository.findByTokenHash(tokenHash)).thenReturn(Optional.of(entity));

        sessionService.revokeByTokenHash(tokenHash);

        ArgumentCaptor<AuthSessionEntity> captor = ArgumentCaptor.forClass(AuthSessionEntity.class);
        verify(authSessionRepository).save(captor.capture());
        assertNotNull(captor.getValue().getRevokedAt());
    }

    @Test
    void revokeByTokenHashSilentWhenAbsent() {
        when(authSessionRepository.findByTokenHash("missing")).thenReturn(Optional.empty());

        sessionService.revokeByTokenHash("missing");

        verify(authSessionRepository, never()).save(any());
    }

    @Test
    void revokeAllOthersKeepsCurrentSession() {
        OffsetDateTime now = OffsetDateTime.now();
        String currentHash = AuthCryptoUtil.sha256Hex("current");
        AuthSessionEntity currentSession = session(currentHash, now.minusDays(1), now.plusDays(6), now.minusDays(1));
        AuthSessionEntity otherSession = session(AuthCryptoUtil.sha256Hex("other"),
                now.minusDays(1), now.plusDays(6), now.minusDays(1));
        when(authSessionRepository.findByUserIdAndRevokedAtIsNull(USER_ID))
                .thenReturn(List.of(currentSession, otherSession));

        int revoked = sessionService.revokeAllOthers(USER_ID, currentHash);

        assertEquals(1, revoked);
        assertNull(currentSession.getRevokedAt(), "当前会话不应被吊销");
        assertNotNull(otherSession.getRevokedAt(), "其他会话应被吊销");
    }
}
