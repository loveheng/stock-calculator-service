package com.zzh.stock_calculator.data.announcement;

import com.rabbitmq.client.Channel;
import com.zzh.stockcalc.contract.MessageEnvelope;
import com.zzh.stockcalc.contract.MessageType;
import com.zzh.stockcalc.contract.MqPolicy;
import com.zzh.stockcalc.contract.message.AnnouncementDonePayload;
import com.zzh.stockcalc.contract.message.AnnouncementFailedPayload;
import com.zzh.stock_calculator.data.announcement.dto.ExtractedDocument;
import com.zzh.stock_calculator.data.announcement.parser.PdfTextExtractor;
import com.zzh.stock_calculator.data.announcement.parser.SlicingService;
import com.zzh.stock_calculator.data.announcement.parser.StructureTreeBuilder;
import com.zzh.stock_calculator.data.announcement.parser.TextCleaner;
import com.zzh.stock_calculator.data.announcement.service.AnnouncementDistillService;
import com.zzh.stock_calculator.data.announcement.service.GroundingValidator;
import com.zzh.stock_calculator.data.llm.AnnouncementWorkerConfig.LlmGateway;
import com.zzh.stock_calculator.data.llm.AnnouncementWorkerConfig.LlmGatewayException;
import com.zzh.stock_calculator.data.mq.ResultPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * AnnouncementProcessWorker 单测（阶段 4 任务 4）：
 * mock CninfoPdfClient / PdfTextExtractor / LlmGateway / ResultPublisher，
 * 结构树、切片、接地校验走真实实现；覆盖 done 载荷、毒丸终态、LLM 瞬时失败、毒消息丢弃。
 */
class AnnouncementProcessWorkerTest {

    private static final String PDF_URL = "http://static.cninfo.com.cn/test.pdf";
    private static final String TRACE_ID = "t1";
    private static final long DELIVERY_TAG = 7L;

    private static final String BODY_100_PLUS =
            "本公司面临多种经营风险，包括市场竞争加剧、原材料价格波动以及汇率变动带来的不确定性。"
                    + "管理层将持续关注宏观环境变化，及时调整经营策略以保障公司稳健发展，"
                    + "并按照监管要求履行信息披露义务。投资者应当仔细阅读本章节全部内容，充分理解相关风险揭示。";

    private CninfoPdfClient cninfoPdfClient;
    private PdfTextExtractor pdfTextExtractor;
    private AnnouncementDistillService distillService;
    private ResultPublisher resultPublisher;
    private Channel channel;
    private AnnouncementProcessWorker worker;

    @BeforeEach
    void setUp() {
        cninfoPdfClient = mock(CninfoPdfClient.class);
        pdfTextExtractor = mock(PdfTextExtractor.class);
        distillService = mock(AnnouncementDistillService.class);
        resultPublisher = mock(ResultPublisher.class);
        channel = mock(Channel.class);
        worker = new AnnouncementProcessWorker(cninfoPdfClient,
                new TextCleaner(new AnnouncementParseProperties()),
                pdfTextExtractor,
                new StructureTreeBuilder(),
                new SlicingService(),
                distillService,
                new GroundingValidator(),
                resultPublisher,
                new ObjectMapper());
    }

    /** 正常链路：任务信封 → 下载 → 解析 → 路由 → 切片 → 蒸馏 → 接地通过 → done 上报 + ack */
    @Test
    void doneHappyPath() throws Exception {
        stubExtraction("第一章 风险因素概述\n" + BODY_100_PLUS);
        when(distillService.route(any())).thenReturn(List.of("1"));
        when(distillService.distill(anyString(), anyString(), any())).thenReturn("风险因素概述");

        worker.onMessage(message(), channel, DELIVERY_TAG);

        ArgumentCaptor<Object> payloadCaptor = ArgumentCaptor.forClass(Object.class);
        verify(resultPublisher).publish(eq(MessageType.RESULT_ANNOUNCEMENT_DONE),
                payloadCaptor.capture(), eq(MqPolicy.PRODUCER_WORKER), eq(TRACE_ID));
        AnnouncementDonePayload done = assertInstanceOf(AnnouncementDonePayload.class, payloadCaptor.getValue());
        assertEquals("A1", done.getAnnouncementId());
        assertTrue(done.getCharCount() >= 100);
        assertEquals(1, done.getPageCount());
        assertEquals("0.2.0", done.getExtractorVersion());
        assertEquals("风险因素概述", done.getSummary());
        verify(resultPublisher, never()).publish(eq(MessageType.RESULT_ANNOUNCEMENT_FAILED),
                any(), anyString(), anyString());
        verify(channel).basicAck(DELIVERY_TAG, false);
    }

    /** 毒丸终态：清洗后正文不足 100 码点 → SKIPPED_NO_TEXT（PERMANENT）上报 + ack，不触达 LLM */
    @Test
    void scannedPdfSkipsToTerminalFailure() throws Exception {
        stubExtraction("太短");
        worker.onMessage(message(), channel, DELIVERY_TAG);

        AnnouncementFailedPayload failed = capturedFailedPayload();
        assertEquals("A1", failed.getAnnouncementId());
        assertEquals("SKIPPED_NO_TEXT", failed.getFailReason());
        assertEquals("PERMANENT", failed.getErrorKind());
        verify(distillService, never()).route(any());
        verify(channel).basicAck(DELIVERY_TAG, false);
    }

    /** LLM 网关异常 → LLM_ROUTE_FAIL（TRANSIENT）上报 + ack，主服务 fail_count 计次后重发 */
    @Test
    void llmFailureReportsTransient() throws Exception {
        stubExtraction("第一章 风险因素概述\n" + BODY_100_PLUS);
        when(distillService.route(any()))
                .thenThrow(new LlmGatewayException("boom", new RuntimeException()));

        worker.onMessage(message(), channel, DELIVERY_TAG);

        AnnouncementFailedPayload failed = capturedFailedPayload();
        assertEquals("LLM_ROUTE_FAIL", failed.getFailReason());
        assertEquals("TRANSIENT", failed.getErrorKind());
        verify(channel).basicAck(DELIVERY_TAG, false);
    }

    /** 毒消息：信封不可解析 → ack 丢弃，不发布任何结果（发布端下轮重发补齐） */
    @Test
    void unparsableEnvelopeIsAckDropped() throws Exception {
        worker.onMessage(new Message("not-json".getBytes(StandardCharsets.UTF_8),
                new MessageProperties()), channel, DELIVERY_TAG);

        verify(channel).basicAck(DELIVERY_TAG, false);
        verifyNoInteractions(resultPublisher, cninfoPdfClient);
    }

    private void stubExtraction(String cleanedText) throws java.io.IOException {
        when(cninfoPdfClient.downloadPdf(PDF_URL)).thenReturn(new byte[]{1, 2, 3});
        when(pdfTextExtractor.extract(any())).thenReturn(ExtractedDocument.of(1, List.of(cleanedText)));
    }

    private Message message() {
        MessageEnvelope envelope = MessageEnvelope.builder()
                .messageId("m1")
                .type(MessageType.TASK_ANNOUNCEMENT_PROCESS)
                .schemaVersion(1)
                .occurredAt(1L)
                .traceId(TRACE_ID)
                .producer("stockcalc-main")
                .payload(Map.of("announcementId", "A1", "title", "2025年度报告",
                        "adjunctUrl", PDF_URL, "secCode", "000001"))
                .build();
        return new Message(new ObjectMapper().writeValueAsString(envelope)
                .getBytes(StandardCharsets.UTF_8), new MessageProperties());
    }

    private AnnouncementFailedPayload capturedFailedPayload() {
        ArgumentCaptor<Object> payloadCaptor = ArgumentCaptor.forClass(Object.class);
        verify(resultPublisher).publish(eq(MessageType.RESULT_ANNOUNCEMENT_FAILED),
                payloadCaptor.capture(), eq(MqPolicy.PRODUCER_WORKER), eq(TRACE_ID));
        return assertInstanceOf(AnnouncementFailedPayload.class, payloadCaptor.getValue());
    }
}
