package com.zzh.stock_calculator.auth.service;

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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RateLimitService 单元测试（决策 B11）：Redis INCR 固定窗口——首击定窗、未超限不重置窗、
 * 超限 429 必须穿透（不得被 fail-open 分支吞掉）、Redis 故障 fail-open 放行。
 */
@ExtendWith(MockitoExtension.class)
class RateLimitServiceTest {

    private static final String KEY = "rl:reg:ip:1.2.3.4";

    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOperations;

    private RateLimitService rateLimitService;

    @BeforeEach
    void setUp() {
        rateLimitService = new RateLimitService(redisTemplate);
    }

    @Test
    void firstHitSetsWindowTtl() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment(KEY)).thenReturn(1L);

        rateLimitService.checkRegister("1.2.3.4");

        verify(redisTemplate).expire(KEY, Duration.ofHours(1));
    }

    @Test
    void underLimitPassesWithoutResettingWindow() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment(KEY)).thenReturn(3L);

        assertDoesNotThrow(() -> rateLimitService.checkRegister("1.2.3.4"));

        verify(redisTemplate, never()).expire(anyString(), any(Duration.class));
    }

    @Test
    void overLimitThrows429() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment(KEY)).thenReturn(6L);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> rateLimitService.checkRegister("1.2.3.4"));

        assertEquals(429, ex.getCode());
        verify(redisTemplate, never()).expire(anyString(), any(Duration.class));
    }

    @Test
    void redisFailureFailsOpen() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment(KEY))
                .thenThrow(new RedisConnectionFailureException("redis down"));

        assertDoesNotThrow(() -> rateLimitService.checkRegister("1.2.3.4"));
    }
}
