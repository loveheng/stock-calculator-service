package com.zzh.stock_calculator.llm.service;

import com.zzh.stock_calculator.common.BusinessException;
import com.zzh.stock_calculator.llm.LlmChainRouter;
import com.zzh.stock_calculator.llm.config.LlmProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * LlmChainRouter 责任链单元测试：优先级顺序、429 快速流转（默认不重试）、
 * 可配置重试、不可重试不重试、健康检查跳过、fallback 兜底、全链失败 503。
 */
@ExtendWith(MockitoExtension.class)
class LlmChainRouterTest {

    private static final String SYS = "system-prompt";
    private static final String USER = "user-message";

    @Mock
    private LlmService gemini;

    @Mock
    private LlmService groq;

    @Mock
    private LlmService fallback;

    private LlmProperties props;
    private LlmChainRouter router;

    @BeforeEach
    void setUp() {
        props = new LlmProperties();
        props.setMaxAttempts(1);
        props.setRetryBackoff(Duration.ofMillis(1));
        router = new LlmChainRouter(List.of(gemini, groq, fallback), props);
    }

    @Test
    void firstChannelSuccessReturnsImmediately() {
        when(gemini.isAvailable()).thenReturn(true);
        when(gemini.chat(SYS, USER)).thenReturn("整理结果");

        assertEquals("整理结果", router.chat(SYS, USER));
        verify(groq, never()).chat(anyString(), anyString());
        verify(fallback, never()).chat(anyString(), anyString());
    }

    @Test
    void rateLimitedFlowsToNextChannelWithoutRetryByDefault() {
        when(gemini.isAvailable()).thenReturn(true);
        when(gemini.chat(anyString(), anyString()))
                .thenThrow(new LlmProviderException("gemini 触发限流, http=429", true));
        when(groq.isAvailable()).thenReturn(true);
        when(groq.chat(SYS, USER)).thenReturn("groq 结果");

        assertEquals("groq 结果", router.chat(SYS, USER));
        verify(gemini, times(1)).chat(anyString(), anyString());
        verify(fallback, never()).chat(anyString(), anyString());
    }

    @Test
    void retryableErrorRetriesWhenMaxAttemptsAboveOne() {
        props.setMaxAttempts(2);
        LlmChainRouter configured = new LlmChainRouter(List.of(gemini, groq, fallback), props);
        when(gemini.isAvailable()).thenReturn(true);
        when(gemini.chat(anyString(), anyString()))
                .thenThrow(new LlmProviderException("gemini 服务端异常, http=500", true));
        when(groq.isAvailable()).thenReturn(true);
        when(groq.chat(SYS, USER)).thenReturn("groq 结果");

        assertEquals("groq 结果", configured.chat(SYS, USER));
        verify(gemini, times(2)).chat(anyString(), anyString());
    }

    @Test
    void nonRetryableErrorMovesOnWithoutRetry() {
        props.setMaxAttempts(2);
        LlmChainRouter configured = new LlmChainRouter(List.of(gemini, groq, fallback), props);
        when(gemini.isAvailable()).thenReturn(true);
        when(gemini.chat(anyString(), anyString()))
                .thenThrow(new LlmProviderException("gemini 鉴权失败, http=401", false));
        when(groq.isAvailable()).thenReturn(true);
        when(groq.chat(SYS, USER)).thenReturn("groq 结果");

        assertEquals("groq 结果", configured.chat(SYS, USER));
        verify(gemini, times(1)).chat(anyString(), anyString());
    }

    @Test
    void skipsUnavailableChannels() {
        when(gemini.isAvailable()).thenReturn(false);
        when(groq.isAvailable()).thenReturn(true);
        when(groq.chat(SYS, USER)).thenReturn("groq 结果");

        assertEquals("groq 结果", router.chat(SYS, USER));
        verify(gemini, never()).chat(anyString(), anyString());
    }

    @Test
    void fallbackGuaranteesResultWhenEnabled() {
        when(gemini.isAvailable()).thenReturn(true);
        when(gemini.chat(anyString(), anyString()))
                .thenThrow(new LlmProviderException("超时", true));
        when(groq.isAvailable()).thenReturn(true);
        when(groq.chat(anyString(), anyString()))
                .thenThrow(new LlmProviderException("http=429", true));
        when(fallback.isAvailable()).thenReturn(true);
        when(fallback.chat(anyString(), anyString())).thenReturn("[降级响应] 模板");

        assertEquals("[降级响应] 模板", router.chat(SYS, USER));
    }

    @Test
    void allFailThrows503WhenFallbackDisabled() {
        when(gemini.isAvailable()).thenReturn(true);
        when(gemini.chat(anyString(), anyString()))
                .thenThrow(new LlmProviderException("超时", true));
        when(groq.isAvailable()).thenReturn(true);
        when(groq.chat(anyString(), anyString()))
                .thenThrow(new LlmProviderException("http=429", true));
        when(fallback.isAvailable()).thenReturn(false);

        BusinessException ex = assertThrows(BusinessException.class, () -> router.chat(SYS, USER));

        assertEquals(503, ex.getCode());
        assertTrue(ex.getMessage().contains("所有 LLM 渠道均不可用"));
        assertTrue(ex.getMessage().contains("http=429"));
    }

    @Test
    void blankPromptRejected() {
        BusinessException ex = assertThrows(BusinessException.class, () -> router.chat(" ", USER));
        assertEquals(400, ex.getCode());
        verify(gemini, never()).chat(anyString(), anyString());
    }

    @Test
    void routerPassesExactPromptsToChannel() {
        when(gemini.isAvailable()).thenReturn(true);
        when(gemini.chat(anyString(), anyString())).thenReturn("ok");

        router.chat(SYS, USER);

        verify(gemini).chat(argThat(SYS::equals), argThat(USER::equals));
    }

    @Test
    void isDegradedResponseMatchesFallbackTemplateOnly() {
        assertTrue(router.isDegradedResponse(props.getFallback().getResponse()));
        assertFalse(router.isDegradedResponse("[降级响应] 模板"));
        assertFalse(router.isDegradedResponse("正常模型输出"));
        assertFalse(router.isDegradedResponse(null));
    }
}
