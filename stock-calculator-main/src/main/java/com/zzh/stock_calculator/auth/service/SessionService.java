package com.zzh.stock_calculator.auth.service;
import com.zzh.stock_calculator.common.AuthErrorCode;
import com.zzh.stock_calculator.common.BusinessException;
import com.zzh.stock_calculator.auth.config.AuthProperties;
import com.zzh.stock_calculator.auth.dto.AuthSessionResponse;
import com.zzh.stock_calculator.auth.entity.AuthSessionEntity;
import com.zzh.stock_calculator.auth.repository.AuthSessionRepository;
import com.zzh.stock_calculator.auth.util.AuthCryptoUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 不透明会话令牌服务（docs/e2ee-auth-backend-design.md §D.4.3，决策 B3）。
 *
 * @description token 原文仅存在于签发响应体，落库为 SHA-256；校验失败一律 401。
 *              吊销以表为准：logout 吊销当前、改密吊销他端（决策 B4），无 JWT、无隐式状态。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.auth.enabled", havingValue = "true")
public class SessionService {

    public static final String SCOPE_FULL = "full";
    public static final String SCOPE_RECOVERY = "recovery";

    private final AuthSessionRepository authSessionRepository;
    private final AuthProperties authProperties;

    /** 签发全量会话（register / login / 找回成功后）；ttlDays 夹取 [1, sessionTtlMaxDays] */
    public AuthSessionResponse issueFull(UUID userId, int ttlDays) {
        int days = Math.min(Math.max(ttlDays, 1), authProperties.getSessionTtlMaxDays());
        return issue(userId, SCOPE_FULL, Duration.ofDays(days));
    }

    /** 签发 recovery 受限会话（找回验证通过后，硬过期短 TTL，仅可调 confirm 与 logout） */
    public AuthSessionResponse issueRecovery(UUID userId) {
        return issue(userId, SCOPE_RECOVERY, Duration.ofMinutes(authProperties.getRecoverySessionMinutes()));
    }

    private AuthSessionResponse issue(UUID userId, String scope, Duration ttl) {
        String token = AuthCryptoUtil.randomToken();
        OffsetDateTime now = OffsetDateTime.now();
        AuthSessionEntity entity = AuthSessionEntity.builder()
                .id(UUID.randomUUID())
                .userId(userId)
                .tokenHash(AuthCryptoUtil.sha256Hex(token))
                .scope(scope)
                .expiresAt(now.plus(ttl))
                .lastSeenAt(now)
                .build();
        authSessionRepository.save(entity);
        log.info("session issued, userId={}, scope={}", userId, scope);
        return AuthSessionResponse.builder()
                .userId(userId)
                .token(token)
                .expiresAt(entity.getExpiresAt())
                .build();
    }

    /**
     * 解析 Bearer 令牌：哈希查库 → 吊销/过期校验 → 节流滑动续期。
     *
     * @description 续期节流（《设计》§D.4.3）：距 last_seen_at 超过 TTL/2 才顺延至满 TTL，防写放大；
     *              等价前端 autoRefreshToken / 决策 D3 的服务端侧。
     */
    @Transactional
    public AuthSessionEntity resolve(String token) {
        String tokenHash = AuthCryptoUtil.sha256Hex(token);
        AuthSessionEntity session = authSessionRepository.findByTokenHash(tokenHash)
                .orElseThrow(() -> new BusinessException(AuthErrorCode.UNAUTHORIZED, "会话已失效，请重新登录"));
        OffsetDateTime now = OffsetDateTime.now();
        if (session.getRevokedAt() != null || session.getExpiresAt().isBefore(now)) {
            throw new BusinessException(AuthErrorCode.UNAUTHORIZED, "会话已失效，请重新登录");
        }
        Duration total = Duration.between(session.getCreatedAt(), session.getExpiresAt());
        boolean needsRenew = session.getLastSeenAt() == null
                || Duration.between(session.getLastSeenAt(), now).compareTo(total.dividedBy(2)) > 0;
        if (needsRenew && !total.isZero() && !total.isNegative()) {
            session.setLastSeenAt(now);
            session.setExpiresAt(now.plus(total));
            authSessionRepository.save(session);
        }
        return session;
    }

    /** 登出：按 tokenHash 吊销当前会话（幂等，缺失静默） */
    @Transactional
    public void revokeByTokenHash(String tokenHash) {
        authSessionRepository.findByTokenHash(tokenHash).ifPresent(session -> {
            if (session.getRevokedAt() == null) {
                session.setRevokedAt(OffsetDateTime.now());
                authSessionRepository.save(session);
                log.info("session revoked, userId={}", session.getUserId());
            }
        });
    }

    /** 改密后吊销全部其他会话（决策 B4），返回吊销数量 */
    @Transactional
    public int revokeAllOthers(UUID userId, String currentTokenHash) {
        List<AuthSessionEntity> actives = authSessionRepository.findByUserIdAndRevokedAtIsNull(userId);
        int revoked = 0;
        for (AuthSessionEntity session : actives) {
            if (!AuthCryptoUtil.constantTimeEquals(session.getTokenHash(), currentTokenHash)) {
                session.setRevokedAt(OffsetDateTime.now());
                authSessionRepository.save(session);
                revoked++;
            }
        }
        return revoked;
    }
}
