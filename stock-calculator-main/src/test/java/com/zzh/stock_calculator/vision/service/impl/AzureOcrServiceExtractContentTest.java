package com.zzh.stock_calculator.vision.service.impl;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.zzh.stock_calculator.vision.config.OcrProperties;
import com.zzh.stock_calculator.vision.service.OcrChannelException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AzureOcrService.extractContent 响应结构解析测试：
 * 覆盖 imageanalysis 4.x / v3.2 各代 content 路径、blocks 逐行兜底、
 * 真·空图不误告警、结构未命中告警与 error 响应体分类（纯解析逻辑，无网络调用）。
 */
class AzureOcrServiceExtractContentTest {

    private AzureOcrService service;
    private Logger logger;
    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void setUp() {
        service = new AzureOcrService(new OcrProperties(), new ObjectMapper());

        logger = (Logger) LoggerFactory.getLogger(AzureOcrService.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        logger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(logAppender);
    }

    @Test
    void readsContentFromImageAnalysis4xPath() {
        String body = """
                {"status":"Succeeded","readResult":{"content":"中国平安 买入 100股"}}""";

        assertEquals("中国平安 买入 100股", service.extractContent(body));
    }

    @Test
    void readsContentFromNestedAnalyzeResultPath() {
        String body = """
                {"status":"Succeeded","analyzeResult":{"readResult":{"content":"第二路径文本"}}}""";

        assertEquals("第二路径文本", service.extractContent(body));
    }

    @Test
    void readsContentFromV32ReadResultsPath() {
        String body = """
                {"status":"Succeeded","analyzeResult":{"readResults":[{"content":"第一页"},{"content":"第二页"}]}}""";

        assertEquals("第一页\n第二页", service.extractContent(body));
    }

    @Test
    void fallsBackToBlocksLinesWhenContentMissing() {
        String body = """
                {"status":"Succeeded","readResult":{"blocks":[
                    {"lines":[{"text":"第一行"},{"text":"第二行"}]},
                    {"lines":[{"text":"第三行"}]}
                ]}}""";

        assertEquals("第一行\n第二行\n第三行", service.extractContent(body));
    }

    /**
     * 生产实测回归（2026-09-01）：该 Azure 资源的 imageanalysis:analyze 响应中
     * readResult 只有 blocks（含 words/boundingPolygon），没有 content 字段，
     * 旧代码三条路径全部未命中静默返回 ""，被门面误判为空文本 422。
     */
    @Test
    void parsesRealWorldResponseWithoutContentField() {
        String body = """
                {"modelVersion":"2023-10-01","metadata":{"width":1116,"height":4959},
                 "readResult":{"blocks":[
                   {"lines":[
                     {"text":"*ST闻泰 600745.SH","boundingPolygon":[{"x":52,"y":260}],
                      "words":[{"text":"*ST","confidence":0.99},{"text":"闻","confidence":0.992}]},
                     {"text":"卖出","boundingPolygon":[{"x":102,"y":382}],
                      "words":[{"text":"卖","confidence":0.996},{"text":"出","confidence":0.996}]},
                     {"text":"成交价格:16.690元","words":[{"text":":16.690","confidence":0.971}]},
                     {"text":"成交数量: 100","words":[{"text":"100","confidence":0.989}]}]}
                 ]}}""";

        String text = service.extractContent(body);

        assertEquals("*ST闻泰 600745.SH\n卖出\n成交价格:16.690元\n成交数量: 100", text);
        assertTrue(logAppender.list.isEmpty());
    }

    @Test
    void trulyEmptyImageReturnsBlankWithoutWarn() {
        String body = """
                {"status":"Succeeded","readResult":{"content":""}}""";

        assertEquals("", service.extractContent(body));
        assertTrue(logAppender.list.isEmpty());
    }

    @Test
    void unmatchedStructureWarnsAndReturnsBlank() {
        String body = """
                {"status":"Succeeded","readResult":{"foo":"bar"}}""";

        assertEquals("", service.extractContent(body));

        assertEquals(1, logAppender.list.size());
        ILoggingEvent event = logAppender.list.getFirst();
        assertEquals(Level.WARN, event.getLevel());
        assertTrue(event.getFormattedMessage().contains("未命中"));
        assertTrue(event.getFormattedMessage().contains("foo"));
    }

    @Test
    void errorBodyThrowsNonRetryable() {
        String body = """
                {"error":{"code":"InvalidImage","message":"图片内容无效"}}""";

        OcrChannelException ex = assertThrows(OcrChannelException.class, () -> service.extractContent(body));

        assertFalse(ex.isRetryable());
        assertTrue(ex.getMessage().contains("图片内容无效"));
    }

    @Test
    void blankBodyReturnsBlank() {
        assertEquals("", service.extractContent("  "));
    }
}
