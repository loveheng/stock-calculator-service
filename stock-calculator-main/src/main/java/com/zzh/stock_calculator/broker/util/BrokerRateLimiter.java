package com.zzh.stock_calculator.broker.util;

import com.zzh.stock_calculator.broker.config.BrokerProperties;
import com.zzh.stock_calculator.common.RateLimitedException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * broker 域限流器（free-canvas v3 §4.2 用户桶；模式仿 search SearchRateLimiter，
 * 不 import search/copilot 内部类型——Modulith 边界）：StringRedisTemplate INCR+EXPIRE
 * 固定窗口、按端点桶字符拆 key、抛 {@link RateLimitedException}（data.retryAfterSeconds）。
 * compute/ask/monitor 桶随 M2/M3/M4 扩展（各自独立桶互不挤占）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BrokerRateLimiter {

    private final StringRedisTemplate redisTemplate;
    private final BrokerProperties properties;

    /** klines 读穿代理桶（rl:broker:klines:{uid}，固定窗口 30/min） */
    public void checkKlines(String userId) {
        int windowSeconds = properties.getRateLimit().getKlinesWindowSeconds();
        hit(userId, "klines", properties.getRateLimit().getKlinesMaxPerWindow(), windowSeconds);
    }

    /** compute 桶（rl:broker:compute:{uid}，固定窗口 ≈30/s 承载 20/s+burst30 矩阵语义） */
    public void checkCompute(String userId) {
        hit(userId, "compute", properties.getRateLimit().getComputeMaxPerWindow(),
                properties.getRateLimit().getComputeWindowSeconds());
    }

    /** ask 桶（rl:broker:ask:{uid}，§4.2 矩阵 1/5s burst2 → 固定窗口 5s×2） */
    public void checkAsk(String userId) {
        hit(userId, "ask", properties.getRateLimit().getAskMaxPerWindow(),
                properties.getRateLimit().getAskWindowSeconds());
    }

    private void hit(String userId, String bucket, int limit, int windowSeconds) {
        long epochSecond = System.currentTimeMillis() / 1000L;
        String key = "rl:broker:" + userId + ":" + bucket + ":" + epochSecond / windowSeconds;
        try {
            Long count = redisTemplate.opsForValue().increment(key);
            if (count == null) {
                return; // 事务/管道模式下可能为 null，视为未计数
            }
            if (count == 1) {
                // 仅首击定窗（固定窗口语义）；INCR/EXPIRE 非原子为已知微小竞态，search/copilot 同款取舍
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
            log.warn("broker rate limit degraded (fail-open), redis unavailable: {}", e.getMessage());
        }
    }
}
