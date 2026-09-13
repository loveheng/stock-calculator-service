package com.zzh.stock_calculator.data.announcement;

import com.sun.net.httpserver.HttpServer;
import com.zzh.stockcalc.contract.MessageEnvelope;
import com.zzh.stockcalc.contract.MessageType;
import com.zzh.stockcalc.contract.MqExchange;
import com.zzh.stockcalc.contract.MqKey;
import com.zzh.stockcalc.contract.MqPolicy;
import com.zzh.stockcalc.contract.MqQueue;
import com.zzh.stockcalc.contract.message.AnnouncementDonePayload;
import com.zzh.stockcalc.contract.message.AnnouncementFailedPayload;
import com.zzh.stockcalc.contract.message.AnnouncementProcessTask;
import com.zzh.stockcalc.contract.message.StructureNode;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * 公告处理 worker 端到端链路（需本地 broker，RABBIT_E2E=true 门控）：task 信封下发 →
 * 真实监听器（手动 ack + prefetch=2）→ PDF 解析/建树/切片/接地全真实实现 →
 * LLM 经本地 HTTP 桩（JDK HttpServer 冒充 OpenAI 兼容 /chat/completions，
 * 按请求体"路由引擎"标记分流路由/蒸馏两段响应）→ result.announcement.done 上行 →
 * 捕获队列断言；失败支路经 mock 下载异常（DOWNLOAD_FAIL/TRANSIENT）验证 failed 上行。
 * CninfoPdfClient 为 @MockitoBean 替身（E2E 不真实打 CNINFO；collector.enabled=false 防真实打
 * CLS API，CollectorGateTest 教训——真实 bean 由 AnnouncementWorkerConfig 装配，测试期替换）。
 * LLM 桩端口随机（@DynamicPropertySource 惰性解析，@BeforeAll 启动后回填）。
 */
@EnabledIfEnvironmentVariable(named = "RABBIT_E2E", matches = "true")
@TestPropertySource(properties = {
        "datasvc.worker.enabled=true",
        "datasvc.collector.enabled=false",
        "datasvc.worker.embedding.account-id=test-account",
        "datasvc.worker.embedding.api-token=test-token",
        "datasvc.llm.api-key=test-key",
        "datasvc.llm.model=test-model"
})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class AnnouncementWorkerE2ETest {

    private static final String PDF_URL = "http://stub.cninfo/e2e.pdf";
    private static final String FAIL_URL = "http://stub.cninfo/boom.pdf";
    private static final String TRACE_DONE = "e2e-ann-done";
    private static final String TRACE_FAIL = "e2e-ann-fail";
    private static final String CAPTURE_QUEUE = "test.announcement.result.capture.q";
    private static final String SUMMARY = "Stub summary of risk factors overview";

    /** 蒸馏桩摘要：纯 ASCII 无数字 → 接地校验零失配，链路确定性通过 */
    private static final List<String> PDF_LINES = List.of(
            "Risk Factors Overview",
            "The company faces various operational risks including intensifying market competition,",
            "raw material price fluctuation and foreign exchange uncertainty. Management will",
            "continue to monitor the environment and adjust strategies in a timely manner to",
            "ensure stable development. Investors should read this section carefully and fully",
            "understand the related risk disclosures before making any investment decisions.");

    private static HttpServer llmStub;
    private static volatile int llmPort;
    private static byte[] pdfBytes;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private AmqpAdmin amqpAdmin;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private CninfoPdfClient cninfoPdfClient;

    @BeforeAll
    static void startLlmStubAndBuildPdf() throws Exception {
        llmStub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        llmPort = llmStub.getAddress().getPort();
        llmStub.createContext("/chat/completions", exchange -> {
            String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            // 阶段一路由（提示词含"路由引擎"标记）→ 回树内 nodeId；蒸馏段 → 回桩摘要
            String content = request.contains("路由引擎") ? "[\"root\"]" : SUMMARY;
            byte[] resp = llmJson(content).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, resp.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(resp);
            }
        });
        llmStub.start();
        pdfBytes = buildPdf();
    }

    @AfterAll
    static void stopLlmStub() {
        if (llmStub != null) {
            llmStub.stop(0);
        }
    }

    /** 动态端口回填 datasvc.llm.base-url（supplier 惰性求值，晚于 @BeforeAll） */
    @DynamicPropertySource
    static void llmStubProps(DynamicPropertyRegistry registry) {
        registry.add("datasvc.llm.base-url", () -> "http://127.0.0.1:" + llmPort);
    }

    /** pdfbox 生成 ASCII 单页 PDF：正文 >100 码点且不命中任何标题模式 → 建树空 → root 兜底覆盖全文 */
    private static byte[] buildPdf() throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 11);
                cs.setLeading(14f);
                cs.newLineAtOffset(40, 750);
                for (String line : PDF_LINES) {
                    cs.showText(line);
                    cs.newLine();
                }
                cs.endText();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    @BeforeEach
    void declareCaptureQueueAndClean() {
        amqpAdmin.declareQueue(QueueBuilder.durable(CAPTURE_QUEUE).build());
        amqpAdmin.declareBinding(org.springframework.amqp.core.BindingBuilder.bind(new Queue(CAPTURE_QUEUE))
                .to(new org.springframework.amqp.core.TopicExchange(MqExchange.RESULTS))
                .with(MqKey.RESULT_ANNOUNCEMENT_DONE));
        amqpAdmin.declareBinding(org.springframework.amqp.core.BindingBuilder.bind(new Queue(CAPTURE_QUEUE))
                .to(new org.springframework.amqp.core.TopicExchange(MqExchange.RESULTS))
                .with(MqKey.RESULT_ANNOUNCEMENT_FAILED));
        amqpAdmin.purgeQueue(CAPTURE_QUEUE, false);
        purgeTaskAndResultQueues();
    }

    @AfterEach
    void cleanup() {
        amqpAdmin.deleteQueue(CAPTURE_QUEUE);
        purgeTaskAndResultQueues();
    }

    private void purgeTaskAndResultQueues() {
        amqpAdmin.purgeQueue(MqQueue.TASK_ANNOUNCEMENT_PROCESS, false);
        amqpAdmin.purgeQueue(MqQueue.TASK_ANNOUNCEMENT_PROCESS_RETRY, false);
        amqpAdmin.purgeQueue(MqQueue.DEAD, false);
        amqpAdmin.purgeQueue(MqQueue.RESULT_INGEST, false);
        amqpAdmin.purgeQueue(MqQueue.RESULT_INGEST_RETRY, false);
    }

    @Test
    @DisplayName("task 下发 → worker 全真解析+桩 LLM 蒸馏 → result.announcement.done 上行 → 队列清空无死信")
    void taskProcessedAndDonePublished() throws Exception {
        when(cninfoPdfClient.downloadPdf(PDF_URL)).thenReturn(pdfBytes);
        publishTask("e2e-ann-001", PDF_URL, TRACE_DONE);

        AnnouncementDonePayload done = awaitResult(TRACE_DONE, MessageType.RESULT_ANNOUNCEMENT_DONE,
                AnnouncementDonePayload.class);

        assertThat(done.getAnnouncementId()).isEqualTo("e2e-ann-001");
        assertThat(done.getExtractorVersion()).isEqualTo("0.2.0");
        assertThat(done.getCharCount()).isGreaterThanOrEqualTo(100);
        assertThat(done.getPageCount()).isEqualTo(1);
        assertThat(done.getSummary()).isEqualTo(SUMMARY);
        assertThat(done.getContent()).isNotBlank();
        assertThat(done.getStructure()).hasSize(1);
        StructureNode root = done.getStructure().get(0);
        assertThat(root.getNodeId()).isEqualTo("root");
        assertThat(done.getSelection()).isNotNull();

        assertThat(queueDepth(MqQueue.TASK_ANNOUNCEMENT_PROCESS)).isZero();
        assertThat(queueDepth(MqQueue.DEAD)).isZero();
    }

    @Test
    @DisplayName("下载失败（CninfoDownloadException）→ failed(DOWNLOAD_FAIL/TRANSIENT) 上行 → 队列清空无死信")
    void downloadFailureReportsFailed() throws Exception {
        when(cninfoPdfClient.downloadPdf(FAIL_URL))
                .thenThrow(new CninfoPdfClient.CninfoDownloadException("mock download incomplete"));
        publishTask("e2e-ann-002", FAIL_URL, TRACE_FAIL);

        AnnouncementFailedPayload failed = awaitResult(TRACE_FAIL, MessageType.RESULT_ANNOUNCEMENT_FAILED,
                AnnouncementFailedPayload.class);

        assertThat(failed.getAnnouncementId()).isEqualTo("e2e-ann-002");
        assertThat(failed.getFailReason()).isEqualTo("DOWNLOAD_FAIL");
        assertThat(failed.getErrorKind()).isEqualTo("TRANSIENT");

        assertThat(queueDepth(MqQueue.TASK_ANNOUNCEMENT_PROCESS)).isZero();
        assertThat(queueDepth(MqQueue.DEAD)).isZero();
    }

    private void publishTask(String announcementId, String adjunctUrl, String traceId) {
        MessageEnvelope envelope = MessageEnvelope.builder()
                .messageId(UUID.randomUUID().toString())
                .type(MessageType.TASK_ANNOUNCEMENT_PROCESS)
                .schemaVersion(MessageEnvelope.CURRENT_SCHEMA_VERSION)
                .occurredAt(System.currentTimeMillis())
                .traceId(traceId)
                .producer(MqPolicy.PRODUCER_MAIN)
                .payload(AnnouncementProcessTask.builder()
                        .announcementId(announcementId)
                        .title("E2E 测试公告")
                        .adjunctUrl(adjunctUrl)
                        .secCode("600000")
                        .build())
                .build();
        rabbitTemplate.convertAndSend(MqExchange.TASKS, MqKey.TASK_ANNOUNCEMENT_PROCESS,
                objectMapper.writeValueAsString(envelope));
    }

    /**
     * 轮询捕获队列最多 15s；按 traceId 过滤（共享 broker 上可能有外部源滞留任务触发的
     * 无关 result，丢弃不匹配信封），命中后校验上行信封三要素（type/producer/traceId）。
     */
    private <T> T awaitResult(String traceId, String expectedType, Class<T> payloadType) throws Exception {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            Message message = rabbitTemplate.receive(CAPTURE_QUEUE, 300);
            if (message == null) {
                continue;
            }
            MessageEnvelope resultEnvelope = objectMapper.readValue(
                    new String(message.getBody(), StandardCharsets.UTF_8), MessageEnvelope.class);
            if (!traceId.equals(resultEnvelope.getTraceId())) {
                continue;
            }
            assertThat(resultEnvelope.getType()).isEqualTo(expectedType);
            assertThat(resultEnvelope.getProducer()).isEqualTo(MqPolicy.PRODUCER_WORKER);
            return objectMapper.convertValue(resultEnvelope.getPayload(), payloadType);
        }
        throw new AssertionError(expectedType + " not received in 15s, traceId=" + traceId);
    }

    private Long queueDepth(String queueName) {
        QueueInformation info = amqpAdmin.getQueueInfo(queueName);
        return info == null ? null : info.getMessageCount();
    }

    /** OpenAI 兼容响应壳：content 置于 choices[0].message.content（与 LlmGateway 解析对齐） */
    private static String llmJson(String content) {
        return "{\"id\":\"stub\",\"object\":\"chat.completion\",\"choices\":[{"
                + "\"index\":0,\"finish_reason\":\"stop\","
                + "\"message\":{\"role\":\"assistant\",\"content\":\""
                + content.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}}],"
                + "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1,\"total_tokens\":2}}";
    }
}
