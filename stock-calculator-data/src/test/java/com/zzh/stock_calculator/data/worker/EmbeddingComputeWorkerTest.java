package com.zzh.stock_calculator.data.worker;

import com.openai.core.http.Headers;
import com.openai.errors.OpenAIIoException;
import com.openai.errors.RateLimitException;
import com.openai.errors.UnauthorizedException;
import com.rabbitmq.client.Channel;
import com.zzh.stockcalc.contract.MessageEnvelope;
import com.zzh.stockcalc.contract.MessageType;
import com.zzh.stockcalc.contract.MqExchange;
import com.zzh.stockcalc.contract.MqKey;
import com.zzh.stockcalc.contract.MqPolicy;
import com.zzh.stockcalc.contract.message.EmbeddingComputeResult;
import com.zzh.stockcalc.contract.message.EmbeddingComputeTask;
import com.zzh.stock_calculator.data.config.WorkerProperties;
import com.zzh.stock_calculator.data.mq.ResultPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.embedding.EmbeddingResponseMetadata;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * worker 消费端分流语义单元测试（设计文档 §5/§6.1）：真实 ObjectMapper 还原信封，
 * mock 模型/上行/通道，逐分支验证 ack/nack/dead 分流与上行信封字段
 * （PRODUCER_WORKER 角色标识 + traceId 透传）。不依赖 Spring 上下文与 broker。
 */
@ExtendWith(MockitoExtension.class)
class EmbeddingComputeWorkerTest {

    private static final long TEST_ARTICLE_ID = 990000000003L;
    private static final String TRACE_ID = "trace-embed-003";

    @Mock
    private EmbeddingModel embeddingModel;
    @Mock
    private ResultPublisher resultPublisher;
    @Mock
    private Channel channel;
    @Mock
    private RabbitTemplate rabbitTemplate;

    private ObjectMapper objectMapper;
    private WorkerProperties properties;
    private EmbeddingComputeWorker worker;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        properties = new WorkerProperties();
        properties.getEmbedding().setModel("@cf/baai/bge-m3");
        worker = new EmbeddingComputeWorker(embeddingModel, resultPublisher, objectMapper,
                new EmbeddingRateLimiter(1_000_000), properties, rabbitTemplate);
    }

    @Test
    @DisplayName("正常计算 → result 上行（worker 角色标识 + traceId 透传）→ ack")
    void success() throws Exception {
        String text = "E2E worker 向量化链路验证文本";
        String body = envelopeJson(taskPayload(EmbeddingComputeTask.KIND_CLS_ARTICLE, text));
        when(embeddingModel.embedForResponse(List.of(text))).thenReturn(response(1024, 42));

        worker.onMessage(taskMessage(body), channel, 1L);

        ArgumentCaptor<Object> payloadCaptor = ArgumentCaptor.forClass(Object.class);
        verify(resultPublisher).publish(eq(MessageType.RESULT_EMBEDDING_DONE), payloadCaptor.capture(),
                eq(MqPolicy.PRODUCER_WORKER), eq(TRACE_ID));
        EmbeddingComputeResult published = (EmbeddingComputeResult) payloadCaptor.getValue();
        assertThat(published.getKind()).isEqualTo(EmbeddingComputeTask.KIND_CLS_ARTICLE);
        assertThat(published.getRefId()).isEqualTo(TEST_ARTICLE_ID);
        assertThat(published.getModel()).isEqualTo("@cf/baai/bge-m3");
        assertThat(published.getDims()).isEqualTo(1024);
        assertThat(published.getVector()).hasSize(1024);
        assertThat(published.getTokensUsed()).isEqualTo(42L);
        verify(channel).basicAck(1L, false);
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
    }

    @Test
    @DisplayName("未知 kind → ack 丢弃不回报")
    void unknownKindDropped() throws Exception {
        String body = envelopeJson(taskPayload("unknown_kind", "text"));

        worker.onMessage(taskMessage(body), channel, 1L);

        verify(channel).basicAck(1L, false);
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
        verifyNoInteractions(resultPublisher, embeddingModel);
    }

    @Test
    @DisplayName("空文本 → ack 丢弃不回报")
    void blankTextDropped() throws Exception {
        String body = envelopeJson(taskPayload(EmbeddingComputeTask.KIND_CLS_ARTICLE, "   "));

        worker.onMessage(taskMessage(body), channel, 1L);

        verify(channel).basicAck(1L, false);
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
        verifyNoInteractions(resultPublisher, embeddingModel);
    }

    @Test
    @DisplayName("429 RATE_LIMITED → ack 丢弃（不 nack 不回报，PENDING 留给对账器）")
    void rateLimitedDropped() throws Exception {
        String body = envelopeJson(taskPayload(EmbeddingComputeTask.KIND_CLS_ARTICLE, "text"));
        when(embeddingModel.embedForResponse(any()))
                .thenThrow(RateLimitException.builder().headers(minimalHeaders()).build());

        worker.onMessage(taskMessage(body), channel, 1L);

        verify(channel).basicAck(1L, false);
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
        verifyNoInteractions(resultPublisher);
    }

    @Test
    @DisplayName("TRANSIENT IO 异常 → nack 进重试环")
    void transientNacked() throws Exception {
        String body = envelopeJson(taskPayload(EmbeddingComputeTask.KIND_CLS_ARTICLE, "text"));
        when(embeddingModel.embedForResponse(any())).thenThrow(new OpenAIIoException("connection reset"));

        worker.onMessage(taskMessage(body), channel, 1L);

        verify(channel).basicNack(1L, false, false);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
        verifyNoInteractions(resultPublisher);
    }

    @Test
    @DisplayName("401 凭据整体错误 → 直投 dead.q 停放 + ack")
    void fatalAuthParkedToDead() throws Exception {
        String body = envelopeJson(taskPayload(EmbeddingComputeTask.KIND_CLS_ARTICLE, "text"));
        when(embeddingModel.embedForResponse(any()))
                .thenThrow(UnauthorizedException.builder().headers(minimalHeaders()).build());

        worker.onMessage(taskMessage(body), channel, 1L);

        verify(rabbitTemplate).send(eq(MqExchange.DLX),
                eq(MqKey.DEAD_PREFIX + MessageType.TASK_EMBEDDING_COMPUTE), any(Message.class));
        verify(channel).basicAck(1L, false);
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
        verifyNoInteractions(resultPublisher);
    }

    @Test
    @DisplayName("信封解析失败（毒消息）→ nack 进重试环")
    void unparsableEnvelopeNacked() throws Exception {
        worker.onMessage(taskMessage("not-json{"), channel, 1L);

        verify(channel).basicNack(1L, false, false);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
        verifyNoInteractions(resultPublisher, embeddingModel);
    }

    @Test
    @DisplayName("TRANSIENT 且 x-death 达限 → 投 dead.q + ack")
    void transientExhaustedToDead() throws Exception {
        String body = envelopeJson(taskPayload(EmbeddingComputeTask.KIND_CLS_ARTICLE, "text"));
        when(embeddingModel.embedForResponse(any())).thenThrow(new OpenAIIoException("connection reset"));

        worker.onMessage(taskMessage(body, MqPolicy.MAX_DELIVERY_ATTEMPTS - 1), channel, 1L);

        verify(rabbitTemplate).send(eq(MqExchange.DLX),
                eq(MqKey.DEAD_PREFIX + MessageType.TASK_EMBEDDING_COMPUTE), any(Message.class));
        verify(channel).basicAck(1L, false);
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
        verifyNoInteractions(resultPublisher);
    }

    // ==================== 测试脚手架 ====================

    private static EmbeddingComputeTask taskPayload(String kind, String text) {
        return EmbeddingComputeTask.builder()
                .kind(kind)
                .refId(TEST_ARTICLE_ID)
                .text(text)
                .build();
    }

    /** 真实序列化整信封：type/routing key 与生产端约定一致（排障对齐语义） */
    private String envelopeJson(EmbeddingComputeTask payload) {
        MessageEnvelope envelope = MessageEnvelope.builder()
                .messageId("m-" + System.nanoTime())
                .type(MessageType.TASK_EMBEDDING_COMPUTE)
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
        props.setType(MessageType.TASK_EMBEDDING_COMPUTE);
        return new Message(body.getBytes(StandardCharsets.UTF_8), props);
    }

    private Message taskMessage(String body, int deathCount) {
        MessageProperties props = new MessageProperties();
        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        props.setType(MessageType.TASK_EMBEDDING_COMPUTE);
        props.setHeader("x-death", List.of(Map.of("count", deathCount)));
        return new Message(body.getBytes(StandardCharsets.UTF_8), props);
    }

    private static EmbeddingResponse response(int dims, int totalTokens) {
        EmbeddingResponseMetadata metadata = new EmbeddingResponseMetadata();
        metadata.setModel("@cf/baai/bge-m3");
        metadata.setUsage(new Usage() {
            @Override
            public Integer getPromptTokens() {
                return totalTokens;
            }

            @Override
            public Integer getCompletionTokens() {
                return 0;
            }

            @Override
            public Integer getTotalTokens() {
                return totalTokens;
            }

            @Override
            public Object getNativeUsage() {
                return Map.of();
            }
        });
        return new EmbeddingResponse(List.of(new Embedding(new float[dims], 0)), metadata);
    }

    private static Headers minimalHeaders() {
        return Headers.builder().put("x-test", "1").build();
    }
}
