package com.zzh.stock_calculator.vision.service;

import com.zzh.stock_calculator.common.BusinessException;
import com.zzh.stock_calculator.copilot.CopilotPromptResolver;
import com.zzh.stock_calculator.llm.LlmChainRouter;
import com.zzh.stock_calculator.vision.config.VisionAiProperties;
import com.zzh.stock_calculator.vision.dto.StockCandidate;
import com.zzh.stock_calculator.vision.dto.TradeDraftItem;
import com.zzh.stock_calculator.vision.enums.TradeDirection;
import com.zzh.stock_calculator.vision.enums.TradeStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.util.DigestUtils;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
 * 交易草稿管道（结果缓存命中、强制刷新审查模式、降级输出不缓存、解析失败不缓存、空图 400）+
 * 股票代码补全（唯一候选静默回填、多候选/零匹配透传前端、旧缓存结构惰性回填且不重复查询）。
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

    @Mock
    private StockCodeResolver stockCodeResolver;

    @Mock
    private CopilotPromptResolver promptResolver;

    private ImageTextProcessingFacade facade;

    /** 内存假缓存：绕开 Redis，验证缓存命中/淘汰/回写行为本身 */
    private InMemoryVisionCacheStore cacheStore;

    private static class InMemoryVisionCacheStore implements VisionCacheStore {
        final Map<String, String> store = new HashMap<>();
        @Override public String get(String key) { return store.get(key); }
        @Override public void put(String key, String value, Duration ttl) { store.put(key, value); }
        @Override public void evict(String key) { store.remove(key); }
    }

    @BeforeEach
    void setUp() {
        cacheStore = new InMemoryVisionCacheStore();
        // PromptFormatter、Jackson 与结果缓存用真实实现，校验真实数据流（含 JSON 往返）与缓存行为
        // mock resolver 未打桩返回 null → PromptFormatter 全部走内置常量（fail-open 默认态）
        facade = new ImageTextProcessingFacade(ocrChainManager, new PromptFormatter(promptResolver), llmChainRouter,
                tradeDraftParser, stockCodeResolver, new VisionAiProperties(), new ObjectMapper(), cacheStore);
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

    private TradeDraftItem draftWithoutCode() {
        return TradeDraftItem.builder()
                .stockName("*ST闻泰")
                .direction(TradeDirection.SELL)
                .price(new BigDecimal("16.69"))
                .volume(100)
                .tradeTime("2026-09-01 10:00:00")
                .status(TradeStatus.FILLED)
                .build();
    }

    // ========== 股票代码补全（enrichStockCodes + 旧缓存回填） ==========

    @Test
    void uniqueCandidateAutoFillsStockCodeAndCachesEnrichedResult() {
        when(ocrChainManager.recognizeText(IMAGE)).thenReturn("*ST闻泰 卖出");
        when(llmChainRouter.chat(anyString(), anyString()))
                .thenReturn("[[\"*ST闻泰\",\"SELL\",16.69,100,\"2026-09-01 10:00:00\"]]");
        when(tradeDraftParser.parse(anyString())).thenReturn(List.of(draftWithoutCode()));
        when(llmChainRouter.isDegradedResponse(anyString())).thenReturn(false);
        when(stockCodeResolver.search("*ST闻泰"))
                .thenReturn(List.of(new StockCandidate("sh", "600745", "*ST闻泰", "GP-A")));

        List<TradeDraftItem> drafts = facade.processImageToTradeDrafts(IMAGE, true);

        assertEquals(1, drafts.size());
        assertEquals("600745", drafts.getFirst().getStockCode());
        assertTrue(drafts.getFirst().getCandidates().isEmpty());
        verify(stockCodeResolver, times(1)).search("*ST闻泰");

        // 第二次命中缓存：补全结果随缓存返回，resolver 不再查询
        List<TradeDraftItem> second = facade.processImageToTradeDrafts(IMAGE, true);
        assertEquals("600745", second.getFirst().getStockCode());
        verify(stockCodeResolver, times(1)).search("*ST闻泰");
    }

    @Test
    void multiCandidatesTransferredToFrontendWithNullCode() {
        when(ocrChainManager.recognizeText(IMAGE)).thenReturn("沪深300ETF 买入");
        when(llmChainRouter.chat(anyString(), anyString()))
                .thenReturn("[[\"沪深300ETF\",\"BUY\",3.85,10000,\"2026-09-01 10:00:00\"]]");
        when(tradeDraftParser.parse(anyString())).thenReturn(List.of(
                TradeDraftItem.builder()
                        .stockName("沪深300ETF")
                        .direction(TradeDirection.BUY)
                        .price(new BigDecimal("3.85"))
                        .volume(10000)
                        .tradeTime("2026-09-01 10:00:00")
                        .status(TradeStatus.FILLED)
                        .build()));
        when(llmChainRouter.isDegradedResponse(anyString())).thenReturn(false);
        when(stockCodeResolver.search("沪深300ETF")).thenReturn(List.of(
                new StockCandidate("sh", "510300", "沪深300ETF华泰柏瑞", "ETF"),
                new StockCandidate("sh", "510310", "沪深300ETF易方达", "ETF")));

        List<TradeDraftItem> drafts = facade.processImageToTradeDrafts(IMAGE, true);

        assertNull(drafts.getFirst().getStockCode());
        assertEquals(2, drafts.getFirst().getCandidates().size());
    }

    @Test
    void zeroMatchSetsEmptyCandidatesWithNullCode() {
        when(ocrChainManager.recognizeText(IMAGE)).thenReturn("不存在的股票 卖出");
        when(llmChainRouter.chat(anyString(), anyString()))
                .thenReturn("[[\"不存在的股票\",\"SELL\",16.69,100,\"2026-09-01 10:00:00\"]]");
        when(tradeDraftParser.parse(anyString())).thenReturn(List.of(
                TradeDraftItem.builder()
                        .stockName("不存在的股票")
                        .direction(TradeDirection.SELL)
                        .price(new BigDecimal("16.69"))
                        .volume(100)
                        .tradeTime("2026-09-01 10:00:00")
                        .status(TradeStatus.FILLED)
                        .build()));
        when(llmChainRouter.isDegradedResponse(anyString())).thenReturn(false);
        when(stockCodeResolver.search("不存在的股票")).thenReturn(List.of());

        List<TradeDraftItem> drafts = facade.processImageToTradeDrafts(IMAGE, true);

        assertNull(drafts.getFirst().getStockCode());
        assertTrue(drafts.getFirst().getCandidates().isEmpty());
    }

    @Test
    void legacyCacheWithoutCandidatesBackfilledOnHit() {
        String legacyJson = "[{\"stockCode\":null,\"stockName\":\"中际旭创\",\"direction\":\"BUY\",\"price\":16.69,\"volume\":100,\"tradeTime\":\"2026-09-01 10:00:00\",\"status\":\"FILLED\"}]";
        cacheStore.put("vision:ai:draft:" + DigestUtils.md5DigestAsHex(IMAGE), legacyJson, Duration.ZERO);
        when(stockCodeResolver.search("中际旭创"))
                .thenReturn(List.of(new StockCandidate("sh", "600745", "中际旭创", "GP-A")));

        List<TradeDraftItem> drafts = facade.processImageToTradeDrafts(IMAGE, true);

        assertEquals("600745", drafts.getFirst().getStockCode());
        // 全程未走 OCR/LLM（纯缓存命中 + 回填）
        verify(ocrChainManager, never()).recognizeText(any(byte[].class));
        verify(llmChainRouter, never()).chat(anyString(), anyString());

        // 回写缓存后：再次命中不再是旧结构，resolver 不重复查询
        List<TradeDraftItem> second = facade.processImageToTradeDrafts(IMAGE, true);
        assertEquals("600745", second.getFirst().getStockCode());
        verify(stockCodeResolver, times(1)).search("中际旭创");
    }

    @Test
    void cachedAmbiguousDraftNotRequeried() {
        String ambiguousJson = "[{\"stockCode\":null,\"stockName\":\"沪深300ETF\",\"direction\":\"BUY\",\"price\":3.85,\"volume\":10000,\"tradeTime\":\"2026-09-01 10:00:00\",\"status\":\"FILLED\",\"candidates\":[{\"market\":\"sh\",\"code\":\"510300\",\"name\":\"沪深300ETF华泰柏瑞\",\"type\":\"ETF\"}]}]";
        cacheStore.put("vision:ai:draft:" + DigestUtils.md5DigestAsHex(IMAGE), ambiguousJson, Duration.ZERO);

        List<TradeDraftItem> drafts = facade.processImageToTradeDrafts(IMAGE, true);

        assertNull(drafts.getFirst().getStockCode());
        assertEquals(1, drafts.getFirst().getCandidates().size());
        // 已解析过的多候选草稿：candidates 已落缓存，不重复打 Smartbox
        verify(stockCodeResolver, never()).search(anyString());
    }
}
