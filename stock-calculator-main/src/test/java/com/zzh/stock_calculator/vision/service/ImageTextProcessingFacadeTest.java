package com.zzh.stock_calculator.vision.service;

import com.zzh.stock_calculator.common.BusinessException;
import com.zzh.stock_calculator.llm.LlmChainRouter;
import com.zzh.stock_calculator.vision.config.VisionAiProperties;
import com.zzh.stock_calculator.vision.dto.TradeDraftItem;
import com.zzh.stock_calculator.vision.enums.TradeDirection;
import com.zzh.stock_calculator.vision.enums.TradeStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ImageTextProcessingFacade 门面编排测试：
 * 通用分析管道（OCR -> 清洗 -> LLM 顺序与数据流、空文本拦截、OCR 失败透传、默认任务指令）+
 * 交易草稿管道（结果缓存命中、强制刷新审查模式、降级输出不缓存、解析失败不缓存、空图 400）。
 */
@ExtendWith(MockitoExtension.class)
class ImageTextProcessingFacadeTest {

    private static final byte[] IMAGE = "fake-image-bytes".getBytes(StandardCharsets.UTF_8);

    @Mock
    private OcrChainManager ocrChainManager;

    @Mock
    private LlmChainRouter llmChainRouter;

    @Mock
    private TradeDraftParser tradeDraftParser;

    private ImageTextProcessingFacade facade;

    @BeforeEach
    void setUp() {
        // PromptFormatter 与结果缓存用真实实现，校验真实数据流与缓存行为
        facade = new ImageTextProcessingFacade(ocrChainManager, new PromptFormatter(), llmChainRouter,
                tradeDraftParser, new VisionAiProperties());
    }

    // ========== 通用文本分析管道（processImageToAiResult） ==========

    @Test
    void happyPathOrchestratesOcrThenPromptThenLlm() {
        when(ocrChainManager.recognizeText(IMAGE)).thenReturn("  600745 中际旭创  \n\n\n\n买入 100股 \n");
        when(llmChainRouter.chat(anyString(), anyString())).thenReturn("整理结果");

        String result = facade.processImageToAiResult(IMAGE, "提取交易记录");

        assertEquals("整理结果", result);
        verify(llmChainRouter).chat(
                argThat(sys -> sys.contains("OCR")),
                argThat(user -> user.contains("提取交易记录")
                        && user.contains("600745 中际旭创\n\n买入 100股")));
    }

    @Test
    void emptyOcrTextThrows422WithoutCallingLlm() {
        when(ocrChainManager.recognizeText(IMAGE)).thenReturn("");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> facade.processImageToAiResult(IMAGE, null));

        assertEquals(422, ex.getCode());
        verify(llmChainRouter, never()).chat(anyString(), anyString());
    }

    @Test
    void ocrAllFailPropagatesWithoutCallingLlm() {
        when(ocrChainManager.recognizeText(IMAGE))
                .thenThrow(new BusinessException(503, "所有 OCR 渠道均不可用"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> facade.processImageToAiResult(IMAGE, null));

        assertEquals(503, ex.getCode());
        verify(llmChainRouter, never()).chat(anyString(), anyString());
    }

    @Test
    void blankTaskFallsBackToDefaultInstruction() {
        when(ocrChainManager.recognizeText(IMAGE)).thenReturn("600745 中际旭创");
        when(llmChainRouter.chat(anyString(), anyString())).thenReturn("结果");

        facade.processImageToAiResult(IMAGE, "   ");

        verify(llmChainRouter).chat(anyString(), argThat(user -> user.contains("整理并总结")));
    }

    // ========== 交易草稿管道（processImageToTradeDrafts） ==========

    @Test
    void tradeDraftsCachedOnSecondCall() {
        when(ocrChainManager.recognizeText(IMAGE)).thenReturn("600745 买入");
        when(llmChainRouter.chat(anyString(), anyString()))
                .thenReturn("[[\"600745\",\"中际旭创\",\"BUY\",16.69,100,\"2026-09-01 10:00:00\"]]");
        when(tradeDraftParser.parse(anyString())).thenReturn(List.of(sampleDraft()));
        when(llmChainRouter.isDegradedResponse(anyString())).thenReturn(false);

        List<TradeDraftItem> first = facade.processImageToTradeDrafts(IMAGE, true);
        List<TradeDraftItem> second = facade.processImageToTradeDrafts(IMAGE, true);

        assertEquals(1, first.size());
        assertEquals(first, second);
        // 第二次命中结果缓存：OCR / LLM / 解析均只执行一次
        verify(ocrChainManager, times(1)).recognizeText(IMAGE);
        verify(llmChainRouter, times(1)).chat(anyString(), anyString());
        verify(tradeDraftParser, times(1)).parse(anyString());
    }

    @Test
    void forceRefreshBypassesCacheAndEnablesStrictReviewPrompt() {
        when(ocrChainManager.recognizeText(IMAGE)).thenReturn("600745 买入");
        when(llmChainRouter.chat(anyString(), anyString()))
                .thenReturn("[[\"600745\",\"中际旭创\",\"BUY\",16.69,100,\"2026-09-01 10:00:00\"]]");
        when(tradeDraftParser.parse(anyString())).thenReturn(List.of(sampleDraft()));
        when(llmChainRouter.isDegradedResponse(anyString())).thenReturn(false);

        facade.processImageToTradeDrafts(IMAGE, true);
        List<TradeDraftItem> refreshed = facade.processImageToTradeDrafts(IMAGE, false);

        assertEquals(1, refreshed.size());
        // 强制刷新：不命中缓存，OCR 与 LLM 均重新执行
        verify(ocrChainManager, times(2)).recognizeText(IMAGE);
        verify(llmChainRouter, times(2)).chat(anyString(), anyString());
        // 第二次调用（useCache=false）的 System Prompt 必须启用审查模式
        verify(llmChainRouter).chat(argThat(sys -> sys.contains("审查模式")), anyString());
    }

    @Test
    void degradedResponseThrows503AndNeverCached() {
        when(ocrChainManager.recognizeText(IMAGE)).thenReturn("600745 买入");
        when(llmChainRouter.chat(anyString(), anyString()))
                .thenReturn("[降级响应] AI 渠道暂不可用，本次结果未经模型处理，请稍后重试。");
        when(llmChainRouter.isDegradedResponse(anyString())).thenReturn(true);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> facade.processImageToTradeDrafts(IMAGE, true));

        assertEquals(503, ex.getCode());
        verify(tradeDraftParser, never()).parse(anyString());

        // 降级结果未写缓存：再次请求重新走 LLM 责任链
        assertThrows(BusinessException.class, () -> facade.processImageToTradeDrafts(IMAGE, true));
        verify(llmChainRouter, times(2)).chat(anyString(), anyString());
    }

    @Test
    void parseFailurePropagatesWithoutCaching() {
        when(ocrChainManager.recognizeText(IMAGE)).thenReturn("600745 买入");
        when(llmChainRouter.chat(anyString(), anyString())).thenReturn("不是 JSON 的输出");
        when(llmChainRouter.isDegradedResponse(anyString())).thenReturn(false);
        when(tradeDraftParser.parse(anyString()))
                .thenThrow(new BusinessException(500, "数据解析失败，模型返回格式不合规"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> facade.processImageToTradeDrafts(IMAGE, true));

        assertEquals(500, ex.getCode());

        // 解析失败未写缓存：再次请求重新走全链路
        assertThrows(BusinessException.class, () -> facade.processImageToTradeDrafts(IMAGE, true));
        verify(llmChainRouter, times(2)).chat(anyString(), anyString());
    }

    @Test
    void blankImageRejectedWith400() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> facade.processImageToTradeDrafts(new byte[0], true));

        assertEquals(400, ex.getCode());
        verify(ocrChainManager, never()).recognizeText(any(byte[].class));
    }

    private TradeDraftItem sampleDraft() {
        return TradeDraftItem.builder()
                .stockCode("600745")
                .stockName("中际旭创")
                .direction(TradeDirection.BUY)
                .price(new BigDecimal("16.69"))
                .volume(100)
                .tradeTime("2026-09-01 10:00:00")
                .status(TradeStatus.FILLED)
                .build();
    }
}
