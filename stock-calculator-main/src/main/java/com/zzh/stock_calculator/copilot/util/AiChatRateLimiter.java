package com.zzh.stock_calculator.copilot.util;

import com.zzh.stock_calculator.common.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Copilot 请求限流器（决策 C7：Redis INCR+EXPIRE 固定双窗口，per-minute=10 / per-day=100）。
 * <p>模式对齐 auth 域 RateLimitService 但不 import auth 子包（Modulith 边界）；
 * 键前缀 rl:copilot:，重启不清零；故障策略 fail-open：Redis 不可用时放行并告警。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiChatRateLimiter {

    private static final int PER_MINUTE_MAX = 10;
    private static final int PER_DAY_MAX = 100;
    /** 桶 TTL 略长于窗口本身，覆盖跨窗边界即可（固定窗口按窗口序号分桶） */
    private static final Duration MINUTE_BUCKET_TTL = Duration.ofMinutes(2);
    private static final Duration DAY_BUCKET_TTL = Duration.ofHours(25);

    private final StringRedisTemplate redisTemplate;

    /** 检查限流：超过阈值抛 BusinessException(429) */
    public void check(String userId) {
        long now = System.currentTimeMillis();
        // 分钟窗口
        hit("rl:copilot:" + userId + ":m:" + now / 60_000, PER_MINUTE_MAX, MINUTE_BUCKET_TTL, "每分钟");
        // 天窗口
        hit("rl:copilot:" + userId + ":d:" + now / 86_400_000L, PER_DAY_MAX, DAY_BUCKET_TTL, "每日");
    }

    private void hit(String redisKey, int limit, Duration ttl, String windowLabel) {
        try {
            Long count = redisTemplate.opsForValue().increment(redisKey);
            if (count == null) {
                return; // 事务/管道模式下可能为 null，视为未计数
            }
            if (count == 1) {
                // 仅首次写入时定窗（固定窗口语义）；INCR 与 EXPIRE 非原子为已知微小竞态，
                // 最坏后果是单个桶缺 TTL 永久限流，个人规模可接受（重启/手动 DEL 可清）
                redisTemplate.expire(redisKey, ttl);
            }
            if (count > limit) {
                throw new BusinessException(429, String.format("触发 %s限流上限(%d)", windowLabel, limit));
            }
        } catch (BusinessException e) {
            throw e; // 429 必须穿透，不得被降级分支吞掉（原 CacheManager 实现的缺陷）
        } catch (DataAccessException e) {
            log.warn("copilot rate limit degraded (fail-open), redis unavailable: {}", e.getMessage());
        }
    }
}
