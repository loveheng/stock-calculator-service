package com.zzh.stock_calculator.search.util;

import com.zzh.stock_calculator.common.RateLimitedException;
import com.zzh.stock_calculator.search.config.SearchProperties;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SearchRateLimiter 单元测试（仿 AiChatRateLimiterTest，backend-implementation §5.4）：
 * 固定窗口首击定窗、检索(s)/综合(c)分桶互不挤占、超限 429 必须穿透
 * （不得被 fail-open 分支吞掉）、Redis 故障 fail-open 放行。
 */
@ExtendWith(MockitoExtension.class)
class SearchRateLimiterTest {

    private static final String UID = "u-123";

    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOperations;

    private SearchRateLimiter rateLimiter;

    @BeforeEach
    void setUp() {
        // 默认值 = 拍板值：窗口 10s、检索 10 次、综合 3 次（B6）
        rateLimiter = new SearchRateLimiter(redisTemplate, new SearchProperties());
    }

    /** 按分桶分发计数：s=检索桶, c=综合桶 */
    private void stubCount(long searchCount, long compositeCount) {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment(anyString())).thenAnswer(inv -> {
            String key = inv.getArgument(0);
            return key.contains(":s:") ? searchCount : compositeCount;
        });
    }

    @Test
    void firstHitSetsWindowTtl() {
        stubCount(1L, 1L);

        rateLimiter.checkSearch(UID);

        verify(redisTemplate).expire(contains(":s:"), eq(Duration.ofSeconds(20)));
        verify(redisTemplate, never()).expire(contains(":c:"), any(Duration.class));
    }

    @Test
    void underLimitPassesWithoutResettingWindow() {
        stubCount(2L, 1L);

        assertDoesNotThrow(() -> rateLimiter.checkSearch(UID));

        verify(redisTemplate, never()).expire(anyString(), any(Duration.class));
    }

    @Test
    void searchOverLimitThrows429WithRetryAfter() {
        stubCount(11L, 1L);

        RateLimitedException ex = assertThrows(RateLimitedException.class,
                () -> rateLimiter.checkSearch(UID));

        assertTrue(ex.getRetryAfterSeconds() >= 1 && ex.getRetryAfterSeconds() <= 10);
    }

    @Test
    void bucketsAreIndependent() {
        // 检索桶打满不挤占综合桶；综合桶 3 次上限独立生效
        stubCount(11L, 3L);
        assertThrows(RateLimitedException.class, () -> rateLimiter.checkSearch(UID));
        assertDoesNotThrow(() -> rateLimiter.checkComposite(UID));

        stubCount(1L, 4L);
        assertThrows(RateLimitedException.class, () -> rateLimiter.checkComposite(UID));
        assertDoesNotThrow(() -> rateLimiter.checkSearch(UID));
    }

    @Test
    void nullCountTreatedAsNotCounted() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment(anyString())).thenReturn(null);

        assertDoesNotThrow(() -> rateLimiter.checkSearch(UID));
    }

    @Test
    void redisFailureFailsOpen() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment(anyString()))
                .thenThrow(new RedisConnectionFailureException("redis down"));

        assertDoesNotThrow(() -> rateLimiter.checkSearch(UID));
        assertDoesNotThrow(() -> rateLimiter.checkComposite(UID));
    }
}
