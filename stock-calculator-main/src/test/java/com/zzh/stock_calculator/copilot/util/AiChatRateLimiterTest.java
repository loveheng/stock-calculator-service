package com.zzh.stock_calculator.copilot.util;

import com.zzh.stock_calculator.common.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AiChatRateLimiter 单元测试（决策 C7）：Redis INCR 固定双窗口——首击双桶定窗、
 * 分钟/日任一超限 429 必须穿透（不得被 fail-open 分支吞掉）、Redis 故障 fail-open 放行。
 */
@ExtendWith(MockitoExtension.class)
class AiChatRateLimiterTest {

    private static final String UID = "u-123";

    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOperations;

    private AiChatRateLimiter rateLimiter;

    @BeforeEach
    void setUp() {
        rateLimiter = new AiChatRateLimiter(redisTemplate);
    }

    /** 按键内窗口类型分发计数：m=分钟桶, d=日桶 */
    private void stubCount(long minuteCount, long dayCount) {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment(anyString())).thenAnswer(inv -> {
            String key = inv.getArgument(0);
            return key.contains(":m:") ? minuteCount : dayCount;
        });
    }

    @Test
    void firstHitSetsBothWindowTtls() {
        stubCount(1L, 1L);

        rateLimiter.check(UID);

        verify(redisTemplate).expire(startsWith("rl:copilot:" + UID + ":m:"), eq(Duration.ofMinutes(2)));
        verify(redisTemplate).expire(startsWith("rl:copilot:" + UID + ":d:"), eq(Duration.ofHours(25)));
    }

    @Test
    void underLimitPassesWithoutResettingWindow() {
        stubCount(3L, 5L);

        assertDoesNotThrow(() -> rateLimiter.check(UID));

        verify(redisTemplate, never()).expire(anyString(), any(Duration.class));
    }

    @Test
    void minuteOverLimitThrows429() {
        stubCount(11L, 1L);

        BusinessException ex = assertThrows(BusinessException.class, () -> rateLimiter.check(UID));

        assertEquals(429, ex.getCode());
    }

    @Test
    void dayOverLimitThrows429() {
        stubCount(1L, 101L);

        BusinessException ex = assertThrows(BusinessException.class, () -> rateLimiter.check(UID));

        assertEquals(429, ex.getCode());
    }

    @Test
    void redisFailureFailsOpen() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment(anyString()))
                .thenThrow(new RedisConnectionFailureException("redis down"));

        assertDoesNotThrow(() -> rateLimiter.check(UID));
    }
}
