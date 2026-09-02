package com.zzh.stock_calculator.auth.service;

import com.zzh.stock_calculator.auth.entity.AuthSessionEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 会话热读缓存的 Redis 实现（决策 B11）。
 *
 * @description 存 Redis Hash（auth:sess:&lt;tokenHash&gt;），字段全部 epoch millis / 字符串，
 *              规避 Jackson 版本序列化差异；key TTL = min(缓存上限, 会话剩余有效期)，由调用方计算。
 *              Redis 故障一律降级：get 视作未命中、put/evict 静默失败——认证回源 DB，不阻塞主链路。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.auth.enabled", havingValue = "true")
public class RedisSessionCacheStore implements SessionCacheStore {

    private static final String KEY_PREFIX = "auth:sess:";
    private static final String F_USER_ID = "userId";
    private static final String F_SCOPE = "scope";
    private static final String F_EXPIRES_AT = "expiresAt";
    private static final String F_LAST_SEEN_AT = "lastSeenAt";
    private static final String F_REVOKED_AT = "revokedAt";
    private static final String F_CREATED_AT = "createdAt";

    private final StringRedisTemplate redisTemplate;

    @Override
    public AuthSessionEntity get(String tokenHash) {
        try {
            Map<Object, Object> entries = redisTemplate.opsForHash().entries(KEY_PREFIX + tokenHash);
            if (entries.isEmpty()) {
                return null;
            }
            return fromHash(entries);
        } catch (Exception e) {
            log.warn("session cache get degraded, fallback to db: {}", e.getMessage());
            return null;
        }
    }

    @Override
    public void put(String tokenHash, AuthSessionEntity session, Duration ttl) {
        try {
            Map<String, String> fields = new HashMap<>();
            fields.put(F_USER_ID, session.getUserId().toString());
            fields.put(F_SCOPE, session.getScope());
            fields.put(F_EXPIRES_AT, toMillis(session.getExpiresAt()));
            fields.put(F_LAST_SEEN_AT, toMillis(session.getLastSeenAt()));
            fields.put(F_REVOKED_AT, toMillis(session.getRevokedAt()));
            fields.put(F_CREATED_AT, toMillis(session.getCreatedAt()));
            String key = KEY_PREFIX + tokenHash;
            redisTemplate.opsForHash().putAll(key, fields);
            redisTemplate.expire(key, ttl);
        } catch (Exception e) {
            log.debug("session cache put skipped: {}", e.getMessage());
        }
    }

    @Override
    public void evict(String tokenHash) {
        try {
            redisTemplate.delete(KEY_PREFIX + tokenHash);
        } catch (Exception e) {
            log.debug("session cache evict skipped: {}", e.getMessage());
        }
    }

    private static String toMillis(OffsetDateTime time) {
        return time == null ? "" : String.valueOf(time.toInstant().toEpochMilli());
    }

    private static OffsetDateTime fromMillis(String value) {
        return value == null || value.isEmpty()
                ? null : OffsetDateTime.ofInstant(Instant.ofEpochMilli(Long.parseLong(value)), ZoneOffset.UTC);
    }

    private static AuthSessionEntity fromHash(Map<Object, Object> entries) {
        return AuthSessionEntity.builder()
                .userId(UUID.fromString((String) entries.get(F_USER_ID)))
                // tokenHash 仅以 key 形式存在于缓存，实体内无需回填
                .tokenHash("")
                .scope((String) entries.get(F_SCOPE))
                .expiresAt(fromMillis((String) entries.get(F_EXPIRES_AT)))
                .lastSeenAt(fromMillis((String) entries.get(F_LAST_SEEN_AT)))
                .revokedAt(fromMillis((String) entries.get(F_REVOKED_AT)))
                .createdAt(fromMillis((String) entries.get(F_CREATED_AT)))
                .build();
    }
}
