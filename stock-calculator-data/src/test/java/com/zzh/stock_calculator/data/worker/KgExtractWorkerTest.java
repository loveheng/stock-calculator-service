package com.zzh.stock_calculator.data.worker;

import com.openai.errors.OpenAIIoException;
import com.openai.errors.RateLimitException;
import com.openai.errors.UnauthorizedException;
import com.rabbitmq.client.Channel;
import com.zzh.stockcalc.contract.MessageEnvelope;
import com.zzh.stockcalc.contract.MessageType;
import com.zzh.stockcalc.contract.MqExchange;
import com.zzh.stockcalc.contract.MqKey;
import com.zzh.stockcalc.contract.MqPolicy;
import com.zzh.stockcalc.contract.message.KgExtractDonePayload;
import com.zzh.stockcalc.contract.message.KgExtractFailedPayload;
import com.zzh.stockcalc.contract.KgControlledVocabulary;
import com.zzh.stockcalc.contract.message.KgExtractTask;
import com.zzh.llm.LlmRegistry;
import com.zzh.llm.LlmTierProperties;
import com.zzh.llm.TierSpec;
import com.zzh.stock_calculator.data.mq.ResultPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * KG worker 消费端分流语义单元测试（docs/ai-pipeline/cls-news-kg.md §6/§8/D10）：
 * 真实 ObjectMapper 还原信封，mock 模型/上行/通道，逐分支验证 done 上行、失败三分类
 * 回报（PERMANENT/TRANSIENT/RATE_LIMITED）、401 dead 停放与 ack 语义。
 * 不依赖 Spring 上下文与 broker。
 */
@ExtendWith(MockitoExtension.class)
class KgExtractWorkerTest {

    private static final long TEST_ARTICLE_ID = 2487479L;
    private static final String TEST_HASH = "abc123";
    private static final String TRACE_ID = "trace-kg-001";

    @Mock
    private OpenAiChatModel chatModel;
    @Mock
    private ResultPublisher resultPublisher;
    @Mock
    private Channel channel;
    @Mock
    private RabbitTemplate rabbitTemplate;

    private ObjectMapper objectMapper;
    private LlmRegistry llmRegistry;
    private KgExtractWorker worker;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        TierSpec kgSpec = new TierSpec();
        // 三键齐备：LlmRegistry.requireSpec 缺 base-url/api-key 会 fail-fast（成功路径要落 model 名）
        kgSpec.setModel("gemini-2.5-flash");
        kgSpec.setBaseUrl("http://localhost/v1");
        kgSpec.setApiKey("test-key");
        LlmTierProperties tierProps = new LlmTierProperties();
        tierProps.getTiers().put("openai-mini", kgSpec);
        llmRegistry = new LlmRegistry(tierProps);
        worker = new KgExtractWorker(chatModel, resultPublisher, objectMapper,
                rabbitTemplate, llmRegistry);
    }

    @Test
    @DisplayName("正常抽取 → result.kg.done 上行（hash/ctime/model 透传 + traceId）→ ack")
    void success() throws Exception {
        String body = envelopeJson(taskPayload(VALID_EXTRACTION_JSON));
        when(chatModel.call(any(Prompt.class))).thenReturn(chatResponse(VALID_EXTRACTION_JSON));

        worker.onMessage(taskMessage(body), channel, 1L);

        ArgumentCaptor<Object> payloadCaptor = ArgumentCaptor.forClass(Object.class);
        verify(resultPublisher).publish(eq(MessageType.RESULT_KG_DONE), payloadCaptor.capture(),
                eq(MqPolicy.PRODUCER_WORKER), eq(TRACE_ID));
        KgExtractDonePayload published = (KgExtractDonePayload) payloadCaptor.getValue();
        assertThat(published.getArticleId()).isEqualTo(TEST_ARTICLE_ID);
        assertThat(published.getContentHash()).isEqualTo(TEST_HASH);
        assertThat(published.getCtime()).isEqualTo(1758200000L);
        assertThat(published.getModel()).isEqualTo("gemini-2.5-flash");
        assertThat(published.getExtraction().getEntities()).hasSize(2);
        assertThat(published.getExtraction().getEntities().get(0).getName()).isEqualTo("国务院");
        assertThat(published.getExtraction().getEvents()).hasSize(1);
        verify(channel).basicAck(1L, false);
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
    }

    @Test
    @DisplayName("模型输出不可解析 → failed PERMANENT 回报（rawTail 留痕）→ ack")
    void unparsableExtractionReportedPermanent() throws Exception {
        String body = envelopeJson(taskPayload("正文内容"));
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(chatResponse("抱歉，我无法从文本中识别结构化信息。"));

        worker.onMessage(taskMessage(body), channel, 1L);

        verify(resultPublisher).publish(eq(MessageType.RESULT_KG_FAILED), argThat(failedCaptorOf(PERMANENT)),
                eq(MqPolicy.PRODUCER_WORKER), eq(TRACE_ID));
        verify(channel).basicAck(1L, false);
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
    }

    @Test
    @DisplayName("429 RATE_LIMITED → failed 回报（触发主服务熔断窗口）→ ack")
    void rateLimitedReported() throws Exception {
        String body = envelopeJson(taskPayload("正文内容"));
        when(chatModel.call(any(Prompt.class)))
                .thenThrow(RateLimitException.builder().headers(minimalHeaders()).build());

        worker.onMessage(taskMessage(body), channel, 1L);

        verify(resultPublisher).publish(eq(MessageType.RESULT_KG_FAILED), argThat(failedCaptorOf(RATE_LIMITED)),
                eq(MqPolicy.PRODUCER_WORKER), eq(TRACE_ID));
        verify(channel).basicAck(1L, false);
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
    }

    @Test
    @DisplayName("TRANSIENT IO 异常 → failed TRANSIENT 回报（每日对账重发节奏）→ ack")
    void transientReported() throws Exception {
        String body = envelopeJson(taskPayload("正文内容"));
        when(chatModel.call(any(Prompt.class))).thenThrow(new OpenAIIoException("connection reset"));

        worker.onMessage(taskMessage(body), channel, 1L);

        verify(resultPublisher).publish(eq(MessageType.RESULT_KG_FAILED), argThat(failedCaptorOf(TRANSIENT)),
                eq(MqPolicy.PRODUCER_WORKER), eq(TRACE_ID));
        verify(channel).basicAck(1L, false);
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
    }

    @Test
    @DisplayName("401 凭据整体错误 → 不回报、直投 dead.q 停放 + ack（状态行留 PENDING）")
    void fatalAuthParkedToDead() throws Exception {
        String body = envelopeJson(taskPayload("正文内容"));
        when(chatModel.call(any(Prompt.class)))
                .thenThrow(UnauthorizedException.builder().headers(minimalHeaders()).build());

        worker.onMessage(taskMessage(body), channel, 1L);

        verify(rabbitTemplate).send(eq(MqExchange.DLX),
                eq(MqKey.DEAD_PREFIX + MessageType.TASK_KG_EXTRACT), any(Message.class));
        verifyNoInteractions(resultPublisher);
        verify(channel).basicAck(1L, false);
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
    }

    @Test
    @DisplayName("system prompt 的受控谓词取自 contract 常量（防 prompt 与入库两侧词表漂移）")
    void systemPromptUsesSharedVocabulary() throws Exception {
        String body = envelopeJson(taskPayload(VALID_EXTRACTION_JSON));
        when(chatModel.call(any(Prompt.class))).thenReturn(chatResponse(VALID_EXTRACTION_JSON));

        worker.onMessage(taskMessage(body), channel, 1L);

        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(promptCaptor.capture());
        String systemText = promptCaptor.getValue().getSystemMessages().stream()
                .map(SystemMessage::getText)
                .collect(Collectors.joining());
        assertThat(systemText)
                .contains("4. 谓词限受控词表：" + KgControlledVocabulary.PREDICATES_PROMPT)
                .doesNotContain(KgControlledVocabulary.PREDICATE_FALLBACK + "/");
    }

    @Test
    @DisplayName("空正文 → failed PERMANENT 回报（防对账每日空转）、模型零调用 → ack")
    void blankContentReportedPermanent() throws Exception {
        String body = envelopeJson(taskPayload("   "));

        worker.onMessage(taskMessage(body), channel, 1L);

        verify(resultPublisher).publish(eq(MessageType.RESULT_KG_FAILED), argThat(failedCaptorOf(PERMANENT)),
                eq(MqPolicy.PRODUCER_WORKER), eq(TRACE_ID));
        verifyNoInteractions(chatModel);
        verify(channel).basicAck(1L, false);
    }

    @Test
    @DisplayName("信封解析失败（毒消息）→ nack 进重试环")
    void unparsableEnvelopeNacked() throws Exception {
        worker.onMessage(taskMessage("not-json{"), channel, 1L);

        verify(channel).basicNack(1L, false, false);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
        verifyNoInteractions(resultPublisher, chatModel);
    }

    // ==================== 测试脚手架 ====================

    private static final String PERMANENT = KgExtractFailedPayload.ERROR_KIND_PERMANENT;
    private static final String TRANSIENT = KgExtractFailedPayload.ERROR_KIND_TRANSIENT;
    private static final String RATE_LIMITED = KgExtractFailedPayload.ERROR_KIND_RATE_LIMITED;

    private static final String VALID_EXTRACTION_JSON = """
            {
              "entities": [
                {"name": "国务院", "type": "ORG", "aliases": ["中华人民共和国国务院"]},
                {"name": "一季度经济数据", "type": "POLICY", "aliases": []}
              ],
              "relations": [
                {"subjectName": "国务院", "objectName": "一季度经济数据", "predicate": "发布", "confidence": 0.9}
              ],
              "events": [
                {"title": "国务院发布一季度经济数据", "detail": null, "time": null,
                 "timeText": "昨日", "eventType": null,
                 "entityNames": ["国务院", "一季度经济数据"]}
              ]
            }
            """;

    private static KgExtractTask taskPayload(String content) {
        return KgExtractTask.builder()
                .articleId(TEST_ARTICLE_ID)
                .contentHash(TEST_HASH)
                .title("9月18日周五《新闻联播》要闻28条")
                .ctime(1758200000L)
                .content(content)
                .build();
    }

    /** 真实序列化整信封：type 与生产端约定一致（排障对齐语义） */
    private String envelopeJson(KgExtractTask payload) throws Exception {
        MessageEnvelope envelope = MessageEnvelope.builder()
                .messageId("m-" + System.nanoTime())
                .type(MessageType.TASK_KG_EXTRACT)
                .schemaVersion(MessageEnvelope.CURRENT_SCHEMA_VERSION)
                .occurredAt(System.currentTimeMillis())
                .traceId(TRACE_ID)
                .producer(MqPolicy.PRODUCER_MAIN)
                .payload(payload)
                .build();
        return objectMapper.writeValueAsString(envelope);
    }

    /** 模拟生产消息：type 头与 routing key 同名（dead 分流断言依赖 safeType 取该头） */
    private Message taskMessage(String body) {
        MessageProperties props = new MessageProperties();
        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        props.setType(MessageType.TASK_KG_EXTRACT);
        return new Message(body.getBytes(StandardCharsets.UTF_8), props);
    }

    private static ChatResponse chatResponse(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    private org.mockito.ArgumentMatcher<Object> failedCaptorOf(String kind) {
        return payload -> payload instanceof KgExtractFailedPayload failed
                && kind.equals(failed.getErrorKind())
                && Long.valueOf(TEST_ARTICLE_ID).equals(failed.getArticleId());
    }

    private static com.openai.core.http.Headers minimalHeaders() {
        return com.openai.core.http.Headers.builder().put("x-test", "1").build();
    }
}
