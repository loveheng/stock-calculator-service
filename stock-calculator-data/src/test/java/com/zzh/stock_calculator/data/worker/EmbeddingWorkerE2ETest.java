package com.zzh.stock_calculator.data.worker;

import com.zzh.stockcalc.contract.MessageEnvelope;
import com.zzh.stockcalc.contract.MessageType;
import com.zzh.stockcalc.contract.MqExchange;
import com.zzh.stockcalc.contract.MqKey;
import com.zzh.stockcalc.contract.MqPolicy;
import com.zzh.stockcalc.contract.MqQueue;
import com.zzh.stockcalc.contract.message.EmbeddingComputeResult;
import com.zzh.stockcalc.contract.message.EmbeddingComputeTask;
import com.zzh.stock_calculator.data.announcement.CninfoPdfClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.embedding.EmbeddingResponseMetadata;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * worker 端到端链路（需本地 broker，RABBIT_E2E=true 门控）：task 信封下发 →
 * 真实监听器竞争消费（手动 ack + prefetch）→ @Primary 桩模型计算 →
 * result.embedding.done 上行 → 捕获队列断言。collector.enabled=false 防真实打
 * CLS API（CollectorGateTest 教训）；测试 id 与其他用例错开（990000000003），
 * 收尾清理任务队列/入库队列/捕获队列。
 */
@EnabledIfEnvironmentVariable(named = "RABBIT_E2E", matches = "true")
@TestPropertySource(properties = {
        "datasvc.worker.enabled=true",
        "datasvc.collector.enabled=false",
        "datasvc.worker.embedding.account-id=test-account",
        "datasvc.worker.embedding.api-token=test-token",
        "datasvc.llm.base-url=http://127.0.0.1:1",
        "datasvc.llm.api-key=dummy-key",
        "datasvc.llm.model=dummy-model"
})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class EmbeddingWorkerE2ETest {

    private static final long TEST_ARTICLE_ID = 990000000003L;
    private static final String TRACE_ID = "e2e-trace-003";
    private static final String CAPTURE_QUEUE = "test.embedding.result.capture.q";

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private AmqpAdmin amqpAdmin;

    @Autowired
    private ObjectMapper objectMapper;

    /**
     * worker 门控链含 AnnouncementProcessWorker（其依赖的 CninfoPdfClient 由
     * AnnouncementWorkerConfig 装配），worker-only 上下文（collector.enabled=false）
     * 直接可启动；测试期用替身避免真实打 CNINFO（同 AnnouncementWorkerE2ETest）。
     */
    @MockitoBean
    private CninfoPdfClient cninfoPdfClient;

    @BeforeEach
    void declareCaptureQueueAndClean() {
        amqpAdmin.declareQueue(QueueBuilder.durable(CAPTURE_QUEUE).build());
        amqpAdmin.declareBinding(org.springframework.amqp.core.BindingBuilder.bind(new Queue(CAPTURE_QUEUE))
                .to(new TopicExchange(MqExchange.RESULTS)).with(MqKey.RESULT_EMBEDDING_DONE));
        amqpAdmin.purgeQueue(CAPTURE_QUEUE, false);
        amqpAdmin.purgeQueue(MqQueue.TASK_EMBEDDING_COMPUTE, false);
        amqpAdmin.purgeQueue(MqQueue.TASK_EMBEDDING_COMPUTE_RETRY, false);
        amqpAdmin.purgeQueue(MqQueue.RESULT_INGEST, false);
        amqpAdmin.purgeQueue(MqQueue.RESULT_INGEST_RETRY, false);
    }

    @AfterEach
    void cleanup() {
        amqpAdmin.deleteQueue(CAPTURE_QUEUE);
        amqpAdmin.purgeQueue(MqQueue.TASK_EMBEDDING_COMPUTE, false);
        amqpAdmin.purgeQueue(MqQueue.TASK_EMBEDDING_COMPUTE_RETRY, false);
        amqpAdmin.purgeQueue(MqQueue.RESULT_INGEST, false);
        amqpAdmin.purgeQueue(MqQueue.RESULT_INGEST_RETRY, false);
    }

    @Test
    @DisplayName("task 下发 → worker 计算（stub 模型）→ result 上行 → 任务队列清空无死信")
    void taskConsumedAndResultPublished() throws Exception {
        MessageEnvelope envelope = MessageEnvelope.builder()
                .messageId(UUID.randomUUID().toString())
                .type(MessageType.TASK_EMBEDDING_COMPUTE)
                .schemaVersion(MessageEnvelope.CURRENT_SCHEMA_VERSION)
                .occurredAt(System.currentTimeMillis())
                .traceId(TRACE_ID)
                .producer(MqPolicy.PRODUCER_MAIN)
                .payload(EmbeddingComputeTask.builder()
                        .kind(EmbeddingComputeTask.KIND_CLS_ARTICLE)
                        .refId(TEST_ARTICLE_ID)
                        .text("E2E worker 向量化链路验证文本")
                        .build())
                .build();
        rabbitTemplate.convertAndSend(MqExchange.TASKS, MqKey.TASK_EMBEDDING_COMPUTE,
                objectMapper.writeValueAsString(envelope));

        EmbeddingComputeResult result = awaitResult();

        assertThat(result.getKind()).isEqualTo(EmbeddingComputeTask.KIND_CLS_ARTICLE);
        assertThat(result.getRefId()).isEqualTo(TEST_ARTICLE_ID);
        assertThat(result.getModel()).isEqualTo("@cf/baai/bge-m3");
        assertThat(result.getDims()).isEqualTo(1024);
        assertThat(result.getVector()).hasSize(1024);
        assertThat(result.getTokensUsed()).isEqualTo(42L);

        assertThat(queueDepth(MqQueue.TASK_EMBEDDING_COMPUTE)).isZero();
        assertThat(queueDepth(MqQueue.DEAD)).isZero();
    }

    /**
     * 轮询捕获队列最多 15s；按 traceId 过滤（共享 broker 上可能有外部源滞留任务触发的
     * 无关 result，丢弃不匹配信封），命中后校验上行信封三要素（type/producer/traceId）。
     */
    private EmbeddingComputeResult awaitResult() throws Exception {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            Message message = rabbitTemplate.receive(CAPTURE_QUEUE, 300);
            if (message == null) {
                continue;
            }
            MessageEnvelope resultEnvelope = objectMapper.readValue(
                    new String(message.getBody(), StandardCharsets.UTF_8), MessageEnvelope.class);
            if (!TRACE_ID.equals(resultEnvelope.getTraceId())) {
                continue;
            }
            assertThat(resultEnvelope.getType()).isEqualTo(MessageType.RESULT_EMBEDDING_DONE);
            assertThat(resultEnvelope.getProducer()).isEqualTo(MqPolicy.PRODUCER_WORKER);
            return objectMapper.convertValue(resultEnvelope.getPayload(), EmbeddingComputeResult.class);
        }
        throw new AssertionError("result.embedding.done not received in 15s, refId=" + TEST_ARTICLE_ID);
    }

    private Long queueDepth(String queueName) {
        QueueInformation info = amqpAdmin.getQueueInfo(queueName);
        return info == null ? null : info.getMessageCount();
    }

    @TestConfiguration
    static class StubEmbeddingModelConfig {

        /** @Primary 桩替换 CF 模型 Bean：E2E 不真实打 CF（凭据为 dummy） */
        @Bean
        @Primary
        public EmbeddingModel stubEmbeddingModel() {
            return new StubEmbeddingModel();
        }
    }

    /** 确定性 1024 维向量桩（首维=文本长度）；tokensUsed 固定 42 便于断言 */
    static final class StubEmbeddingModel implements EmbeddingModel {

        @Override
        public EmbeddingResponse call(EmbeddingRequest request) {
            List<Embedding> embeddings = request.getInstructions().stream()
                    .map(text -> new Embedding(vectorFor(text), 0))
                    .toList();
            EmbeddingResponseMetadata metadata = new EmbeddingResponseMetadata();
            metadata.setModel("stub-embed");
            metadata.setUsage(new Usage() {
                @Override
                public Integer getPromptTokens() {
                    return 42;
                }

                @Override
                public Integer getCompletionTokens() {
                    return 0;
                }

                @Override
                public Integer getTotalTokens() {
                    return 42;
                }

                @Override
                public Object getNativeUsage() {
                    return Map.of();
                }
            });
            return new EmbeddingResponse(embeddings, metadata);
        }

        @Override
        public float[] embed(Document document) {
            return vectorFor(document.getText());
        }

        private static float[] vectorFor(String text) {
            float[] vector = new float[1024];
            vector[0] = text.length();
            return vector;
        }
    }
}
