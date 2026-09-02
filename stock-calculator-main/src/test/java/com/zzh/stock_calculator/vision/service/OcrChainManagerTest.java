package com.zzh.stock_calculator.vision.service;

import com.zzh.stock_calculator.common.BusinessException;
import com.zzh.stock_calculator.vision.config.OcrProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * OcrChainManager 责任链单元测试：优先级顺序、失败流转、可重试/不可重试、
 * 哈希缓存命中、业务识别为空、全链失败业务异常。
 */
@ExtendWith(MockitoExtension.class)
class OcrChainManagerTest {

    private static final byte[] IMAGE = "fake-image-bytes".getBytes();

    @Mock
    private OcrService azure;

    @Mock
    private OcrService ocrSpace;

    @Mock
    private OcrService local;

    private OcrChainManager chain;

    /** 内存假缓存：绕开 Redis，验证缓存命中/写入行为本身 */
    private static class InMemoryVisionCacheStore implements VisionCacheStore {
        final Map<String, String> store = new HashMap<>();
        @Override public String get(String key) { return store.get(key); }
        @Override public void put(String key, String value, Duration ttl) { store.put(key, value); }
        @Override public void evict(String key) { store.remove(key); }
    }

    @BeforeEach
    void setUp() {
        OcrProperties props = new OcrProperties();
        props.setMaxAttempts(2);
        props.setRetryBackoff(Duration.ofMillis(1));
        // 按预设优先级装配：azure -> ocrspace -> local（与 Spring @Order 注入顺序一致）
        chain = new OcrChainManager(List.of(azure, ocrSpace, local), props, new InMemoryVisionCacheStore());
    }

    @Test
    void firstChannelSuccessReturnsImmediately() {
        when(azure.isAvailable()).thenReturn(true);
        when(azure.recognizeText(IMAGE, "chs")).thenReturn("600745 中际旭创");

        String result = chain.recognizeText(IMAGE);

        assertEquals("600745 中际旭创", result);
        verify(ocrSpace, never()).recognizeText(any(), anyString());
        verify(local, never()).recognizeText(any(), anyString());
    }

    @Test
    void retryableFailureRetriesThenFallsBackToNextChannel() {
        when(azure.isAvailable()).thenReturn(true);
        when(azure.recognizeText(any(), anyString()))
                .thenThrow(new OcrChannelException("azure 限流, http=429", true));
        when(ocrSpace.isAvailable()).thenReturn(true);
        when(ocrSpace.recognizeText(IMAGE, "chs")).thenReturn("fallback-text");

        String result = chain.recognizeText(IMAGE);

        assertEquals("fallback-text", result);
        verify(azure, times(2)).recognizeText(any(), anyString());
        verify(local, never()).recognizeText(any(), anyString());
    }

    @Test
    void nonRetryableFailureMovesToNextChannelWithoutRetry() {
        when(azure.isAvailable()).thenReturn(true);
        when(azure.recognizeText(any(), anyString()))
                .thenThrow(new OcrChannelException("azure 鉴权失败, http=401", false));
        when(ocrSpace.isAvailable()).thenReturn(true);
        when(ocrSpace.recognizeText(IMAGE, "chs")).thenReturn("space");

        String result = chain.recognizeText(IMAGE);

        assertEquals("space", result);
        verify(azure, times(1)).recognizeText(any(), anyString());
    }

    @Test
    void skipsUnavailableChannels() {
        when(azure.isAvailable()).thenReturn(false);
        when(ocrSpace.isAvailable()).thenReturn(true);
        when(ocrSpace.recognizeText(IMAGE, "chs")).thenReturn("space");

        String result = chain.recognizeText(IMAGE);

        assertEquals("space", result);
        verify(azure, never()).recognizeText(any(), anyString());
    }

    @Test
    void businessEmptyResultStopsChainAndIsSuccessful() {
        when(azure.isAvailable()).thenReturn(true);
        when(azure.recognizeText(IMAGE, "chs")).thenReturn("");

        String result = chain.recognizeText(IMAGE);

        assertEquals("", result);
        verify(ocrSpace, never()).recognizeText(any(), anyString());
        verify(local, never()).recognizeText(any(), anyString());
    }

    @Test
    void cacheHitSkipsAllChannels() {
        when(azure.isAvailable()).thenReturn(true);
        when(azure.recognizeText(IMAGE, "chs")).thenReturn("text");

        assertEquals("text", chain.recognizeText(IMAGE));
        assertEquals("text", chain.recognizeText(IMAGE));

        verify(azure, times(1)).recognizeText(any(), anyString());
    }

    @Test
    void allChannelsFailThrowsBusinessExceptionWithReasons() {
        when(azure.isAvailable()).thenReturn(true);
        when(azure.recognizeText(any(), anyString()))
                .thenThrow(new OcrChannelException("超时", true));
        when(ocrSpace.isAvailable()).thenReturn(true);
        when(ocrSpace.recognizeText(any(), anyString()))
                .thenThrow(new OcrChannelException("http=429", true));
        when(local.isAvailable()).thenReturn(true);
        when(local.recognizeText(any(), anyString()))
                .thenThrow(new OcrChannelException("gemini 500", true));

        BusinessException ex = assertThrows(BusinessException.class, () -> chain.recognizeText(IMAGE));

        assertEquals(503, ex.getCode());
        assertTrue(ex.getMessage().contains("所有 OCR 渠道均不可用"));
        assertTrue(ex.getMessage().contains("http=429"));
    }

    @Test
    void blankLanguageFallsBackToDefault() {
        when(azure.isAvailable()).thenReturn(true);
        when(azure.recognizeText(IMAGE, "chs")).thenReturn("text");

        chain.recognizeText(IMAGE, "  ");

        verify(azure).recognizeText(IMAGE, "chs");
    }

    @Test
    void emptyImageRejectedBeforeChain() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> chain.recognizeText(new byte[0]));

        assertEquals(400, ex.getCode());
        verify(azure, never()).recognizeText(any(), anyString());
    }
}
