package com.zzh.stock_calculator.search.util;

import com.zzh.stock_calculator.common.RateLimitedException;
import com.zzh.stock_calculator.search.config.SearchProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 搜索限流器（backend-implementation §5.4；模式仿 copilot AiChatRateLimiter，
 * 不 import copilot 内部类型——Modulith 边界）：StringRedisTemplate INCR+EXPIRE 固定窗口、
 * 按窗口序号分桶（TTL=2×窗口）、fail-open（Redis 不可用放行 + warn）。
 * <p>与 copilot 的差异：抛 {@link RateLimitedException}（retryAfterSeconds = 窗口剩余秒数，
 * 前端据此倒计时）；检索(s)与综合(c)分桶计数互不挤占。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SearchRateLimiter {

    private final StringRedisTemplate redisTemplate;
    private final SearchProperties properties;

    /** 检索类端点限流（announcements / cls / stock-profile） */
    public void checkSearch(String userId) {
        hit(userId, 's', properties.getRateLimit().getSearchMaxPerWindow());
    }

    /** 综合摘要限流 */
    public void checkComposite(String userId) {
        hit(userId, 'c', properties.getRateLimit().getCompositeMaxPerWindow());
    }

    private void hit(String userId, char bucket, int limit) {
        int windowSeconds = properties.getRateLimit().getSearchWindowSeconds();
        long epochSecond = System.currentTimeMillis() / 1000L;
        String key = "rl:search:" + userId + ":" + bucket + ":" + epochSecond / windowSeconds;
        try {
            Long count = redisTemplate.opsForValue().increment(key);
            if (count == null) {
                return; // 事务/管道模式下可能为 null，视为未计数
            }
            if (count == 1) {
                // 仅首击定窗（固定窗口语义）；INCR/EXPIRE 非原子为已知微小竞态，copilot 同款取舍
                redisTemplate.expire(key, Duration.ofSeconds(windowSeconds * 2L));
            }
            if (count > limit) {
                long retryAfter = windowSeconds - (epochSecond % windowSeconds);
                throw new RateLimitedException(retryAfter,
                        String.format("请求过于频繁，请 %d 秒后重试", retryAfter));
            }
        } catch (RateLimitedException e) {
            throw e; // 429 必须穿透，不得被 fail-open 分支吞掉
        } catch (DataAccessException e) {
            log.warn("search rate limit degraded (fail-open), redis unavailable: {}", e.getMessage());
        }
    }
}
