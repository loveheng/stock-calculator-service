package com.zzh.stock_calculator.copilot;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CopilotPromptResolver 单元测试（纯 JUnit，无 Spring 上下文 / 无 DB；同步器见 CopilotPromptSyncTest）。
 * 语义（DB 唯一来源 + Redis 镜像，Redis 为唯一运行时读取源）：
 * 标签链 focusBlockId → scopeId → 页面段 → generic 逐级读 Redis，命中即返回（DEL = 移除该级）；
 * 全部未命中或 Redis 不可用（任一读取异常）→ 代码内兜底人设。
 */
@ExtendWith(MockitoExtension.class)
class CopilotPromptResolverTest {

    private static final String BLOCK_KEY = "copilot:prompt:home:short_term";
    private static final String SCOPE_KEY = "copilot:prompt:home:600519.SH";
    private static final String PAGE_KEY = "copilot:prompt:home";
    private static final String GENERIC_KEY = "copilot:prompt:generic";
    private static final String FALLBACK = CopilotPromptResolver.FALLBACK_PERSONA;

    @Mock private Function<String, String> reader;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOperations;

    private CopilotPromptResolver resolver() {
        return new CopilotPromptResolver(redisTemplate);
    }

    // ==================== Redis 标签链（正常态） ====================

    @Test
    void blockTag_redisHit_returnsOverrideAndSkipsLowerTags() {
        when(reader.apply(BLOCK_KEY)).thenReturn("块级覆写模版");

        String persona = resolver().resolve("home:600519.SH", "home:short_term", reader);

        assertEquals("块级覆写模版", persona);
        verify(reader, never()).apply(SCOPE_KEY);
        verify(reader, never()).apply(PAGE_KEY);
        verify(reader, never()).apply(GENERIC_KEY);
    }

    @Test
    void blockTag_miss_scopeTag_redisHit() {
        when(reader.apply(BLOCK_KEY)).thenReturn(null);
        when(reader.apply(SCOPE_KEY)).thenReturn("scope 覆写模版");

        assertEquals("scope 覆写模版", resolver().resolve("home:600519.SH", "home:short_term", reader));
    }

    @Test
    void blockAndScopeTags_miss_pageTag_redisHit() {
        when(reader.apply(BLOCK_KEY)).thenReturn(null);
        when(reader.apply(SCOPE_KEY)).thenReturn(null);
        when(reader.apply(PAGE_KEY)).thenReturn("页面覆写模版");

        assertEquals("页面覆写模版", resolver().resolve("home:600519.SH", "home:short_term", reader));
    }

    // ==================== focusBlockId 缺省：跳过区块级 ====================

    @Test
    void focusBlockIdNull_skipsBlockTag() {
        when(reader.apply(SCOPE_KEY)).thenReturn(null);
        when(reader.apply(PAGE_KEY)).thenReturn("页面覆写模版");

        assertEquals("页面覆写模版", resolver().resolve("home:600519.SH", null, reader));

        verify(reader, never()).apply(BLOCK_KEY);
    }

    @Test
    void focusBlockIdBlank_alsoSkipsBlockTag() {
        when(reader.apply(SCOPE_KEY)).thenReturn("scope 覆写模版");

        assertEquals("scope 覆写模版", resolver().resolve("home:600519.SH", "  ", reader));

        verify(reader, times(1)).apply(anyString()); // 只查了 scope，页面段短路
        verify(reader, never()).apply(PAGE_KEY);
    }

    // ==================== generic 兜底行（标签链尾） ====================

    @Test
    void genericRow_redisHit_usedAsLastResort() {
        // 区块/scope/页面段全未命中，generic 行命中（在线覆写的通用兜底）
        when(reader.apply(BLOCK_KEY)).thenReturn(null);
        when(reader.apply(SCOPE_KEY)).thenReturn(null);
        when(reader.apply(PAGE_KEY)).thenReturn(null);
        when(reader.apply(GENERIC_KEY)).thenReturn("在线改过的通用兜底");

        assertEquals("在线改过的通用兜底", resolver().resolve("home:600519.SH", "home:short_term", reader));
    }

    @Test
    void genericRowDeleted_fallsToCodeFallback() {
        // generic 行也被 DEL（含全链 DEL）→ 代码内兜底人设
        when(reader.apply(anyString())).thenReturn(null);

        assertEquals(FALLBACK, resolver().resolve("home:600519.SH", "home:short_term", reader));
    }

    // ==================== Redis 不可用 → 代码内兜底 ====================

    @Test
    void redisDown_fallsToCodeFallback() {
        when(reader.apply(anyString())).thenThrow(new RuntimeException("redis down"));

        assertEquals(FALLBACK,
                assertDoesNotThrow(() -> resolver().resolve("home:600519.SH", "home:short_term", reader)));
    }

    @Test
    void redisDown_unknownTag_alsoCodeFallback() {
        when(reader.apply(anyString())).thenThrow(new RuntimeException("redis down"));

        assertEquals(FALLBACK,
                assertDoesNotThrow(() -> resolver().resolve("t_calculator", "unknown:block", reader)));
    }

    // ==================== 脏数据防护 ====================

    @Test
    void blankOrOversizedRedisValue_treatedAsMiss() {
        when(reader.apply(BLOCK_KEY)).thenReturn("   ");
        when(reader.apply(SCOPE_KEY)).thenReturn("x".repeat(CopilotPromptResolver.MAX_TEMPLATE_LENGTH + 1));

        // 空白/超长按未配置处理，页面段与 generic 未配置 → 代码内兜底
        assertEquals(FALLBACK, resolver().resolve("home:600519.SH", "home:short_term", reader));
    }

    @Test
    void redisValueTrimmed() {
        when(reader.apply(BLOCK_KEY)).thenReturn("  覆写模版 \n");

        assertEquals("覆写模版", resolver().resolve("home:600519.SH", "home:short_term", reader));
    }

    // ==================== 生产包装：StringRedisTemplate 接线 ====================

    @Test
    void productionResolver_readsThroughStringRedisTemplate() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(BLOCK_KEY)).thenReturn("块级覆写模版");

        assertEquals("块级覆写模版", resolver().resolve("home:600519.SH", "home:short_term"));
        verify(valueOperations, never()).get(SCOPE_KEY);
    }

    @Test
    void productionResolver_redisDown_fallsToCodeFallback() {
        when(redisTemplate.opsForValue()).thenThrow(new RedisConnectionFailureException("down"));

        assertEquals(FALLBACK, resolver().resolve("home:600519.SH", "home:short_term"));
    }

    // ==================== resolveByTag（固定标签直读，跨域模块 API） ====================

    @Test
    void resolveByTag_hitReturnsTrimmedValue() {
        when(reader.apply("copilot:prompt:vision:trade:system")).thenReturn("  模版内容 \n");

        assertEquals("模版内容", resolver().resolveByTag("vision:trade:system", reader));
    }

    @Test
    void resolveByTag_missOrBlankTag_returnsNull() {
        when(reader.apply("copilot:prompt:vision:trade:system")).thenReturn(null);

        assertNull(resolver().resolveByTag("vision:trade:system", reader));
        assertNull(resolver().resolveByTag("   ", reader));
        assertNull(resolver().resolveByTag(null, reader));
    }

    @Test
    void resolveByTag_blankOrOversizedValue_returnsNull() {
        when(reader.apply("copilot:prompt:vision:trade:system")).thenReturn("   ");
        when(reader.apply("copilot:prompt:vision:generic:system"))
                .thenReturn("x".repeat(CopilotPromptResolver.MAX_TEMPLATE_LENGTH + 1));

        assertNull(resolver().resolveByTag("vision:trade:system", reader));
        assertNull(resolver().resolveByTag("vision:generic:system", reader));
    }

    @Test
    void resolveByTag_redisFailure_returnsNullNotThrow() {
        when(reader.apply(anyString())).thenThrow(new RedisConnectionFailureException("down"));

        assertNull(resolver().resolveByTag("vision:trade:system", reader));
    }

    @Test
    void productionResolveByTag_readsThroughStringRedisTemplate() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("copilot:prompt:vision:trade:system")).thenReturn("覆写模版");

        assertEquals("覆写模版", resolver().resolveByTag("vision:trade:system"));
    }
}
