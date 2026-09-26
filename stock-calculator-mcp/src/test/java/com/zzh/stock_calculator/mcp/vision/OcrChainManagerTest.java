package com.zzh.stock_calculator.mcp.vision;

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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * OCR 责任链调度测试（自 main 侧 OcrChainManagerTest 平移收窄为双渠道口径）：
 * 缓存命中/写入语义、渠道流转、可重试退避、全渠道失败聚合。
 */
@ExtendWith(MockitoExtension.class)
class OcrChainManagerTest {

    private static final byte[] IMAGE = "fake-image".getBytes();

    @Mock
    private OcrService azure;

    @Mock
    private OcrService ocrspace;

    @Mock
    private VisionCacheStore cache;

    private OcrProperties properties;

    private OcrChainManager manager;

    /** 内存假缓存：验证命中/写入行为本身 */
    private final Map<String, String> store = new HashMap<>();

    @BeforeEach
    void setUp() {
        properties = new OcrProperties();
        properties.setMaxAttempts(2);
        properties.setRetryBackoff(Duration.ZERO);
        // lenient：channelName 仅在失败汇总/装配日志路径使用，部分用例不触达
        org.mockito.Mockito.lenient().when(azure.channelName()).thenReturn("azure");
        org.mockito.Mockito.lenient().when(ocrspace.channelName()).thenReturn("ocrspace");
        // lenient：内存假缓存直连（put 存 / get 取），空图用例不触达
        org.mockito.Mockito.lenient().when(cache.get(anyString()))
                .thenAnswer(inv -> store.get(inv.getArgument(0, String.class)));
        org.mockito.Mockito.lenient().doAnswer(inv -> {
            store.put(inv.getArgument(0, String.class), inv.getArgument(1, String.class));
            return null;
        }).when(cache).put(anyString(), anyString(), any(Duration.class));
        manager = new OcrChainManager(List.of(azure, ocrspace), properties, cache);
    }

    @Test
    void cacheHitSkipsChannels() {
        store.put("vision:ocr:text:" + md5(), "");
        String result = manager.recognizeText(IMAGE);
        assertEquals("", result);
        verify(azure, never()).recognizeText(any(), anyString());
        verify(ocrspace, never()).recognizeText(any(), anyString());
    }

    @Test
    void firstChannelSuccessWritesCache() {
        when(azure.isAvailable()).thenReturn(true);
        when(azure.recognizeText(IMAGE, "chs")).thenReturn("600745 中际旭创");
        String result = manager.recognizeText(IMAGE);
        assertEquals("600745 中际旭创", result);
        assertEquals("600745 中际旭创", store.get("vision:ocr:text:" + md5()));
        verify(ocrspace, never()).recognizeText(any(), anyString());
    }

    @Test
    void retryableFailureThenFlowToNextChannel() {
        when(azure.isAvailable()).thenReturn(true);
        when(azure.recognizeText(IMAGE, "chs"))
                .thenThrow(new OcrChannelException("azure 限流, http=429", true))
                .thenThrow(new OcrChannelException("azure 限流, http=429", true));
        when(ocrspace.isAvailable()).thenReturn(true);
        when(ocrspace.recognizeText(IMAGE, "chs")).thenReturn("ocrspace 文本");
        String result = manager.recognizeText(IMAGE);
        assertEquals("ocrspace 文本", result);
        verify(azure, org.mockito.Mockito.times(2)).recognizeText(IMAGE, "chs");
    }

    @Test
    void unavailableChannelSkipped() {
        when(azure.isAvailable()).thenReturn(false);
        when(ocrspace.isAvailable()).thenReturn(true);
        when(ocrspace.recognizeText(IMAGE, "chs")).thenReturn("文本");
        assertEquals("文本", manager.recognizeText(IMAGE));
        verify(azure, never()).recognizeText(any(), anyString());
    }

    @Test
    void allChannelsFailAggregatesReasons() {
        when(azure.isAvailable()).thenReturn(true);
        when(azure.recognizeText(eq(IMAGE), anyString()))
                .thenThrow(new OcrChannelException("鉴权失败", false));
        when(ocrspace.isAvailable()).thenReturn(true);
        when(ocrspace.recognizeText(eq(IMAGE), anyString()))
                .thenThrow(new OcrChannelException("免费额度耗尽", true))
                .thenThrow(new OcrChannelException("免费额度耗尽", true));
        OcrChainException e = assertThrows(OcrChainException.class, () -> manager.recognizeText(IMAGE));
        assertEquals(503, e.getCode());
        assertEquals(true, e.getMessage().contains("azure(鉴权失败)"));
        assertEquals(true, e.getMessage().contains("ocrspace(免费额度耗尽)"));
    }

    @Test
    void emptyImageThrows400() {
        OcrChainException e = assertThrows(OcrChainException.class,
                () -> manager.recognizeText(new byte[0]));
        assertEquals(400, e.getCode());
    }

    /** 与 OcrChainManager 相同的 MD5 口径 */
    private String md5() {
        return org.springframework.util.DigestUtils.md5DigestAsHex(IMAGE);
    }
}
