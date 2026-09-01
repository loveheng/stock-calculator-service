package com.zzh.stock_calculator.auth.entity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 不透明会话（docs/e2ee-auth-backend-design.md §D.3.3 / §D.4.3，决策 B3）。
 *
 * @description token 原文仅存在于签发响应体，落库为 SHA-256；吊销以 revoked_at 为准，
 *              无 JWT、无隐式状态，改密/登出即吊销（决策 B4）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "auth_sessions")
public class AuthSessionEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /** SHA-256(token) 小写 hex，唯一 */
    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;

    /** full / recovery（recovery 受限会话仅可调 recovery/confirm 与 logout） */
    @Column(name = "scope", nullable = false, length = 16)
    private String scope;

    @Column(name = "expires_at", nullable = false, columnDefinition = "timestamptz")
    private OffsetDateTime expiresAt;

    /** 节流滑动续期依据（《设计》§D.4.3）：距 last_seen_at 超过 TTL/2 才写库顺延 */
    @Column(name = "last_seen_at", columnDefinition = "timestamptz")
    private OffsetDateTime lastSeenAt;

    @Column(name = "revoked_at", columnDefinition = "timestamptz")
    private OffsetDateTime revokedAt;

    @CreationTimestamp
    @Column(name = "created_at", columnDefinition = "timestamptz", updatable = false)
    private OffsetDateTime createdAt;
}
