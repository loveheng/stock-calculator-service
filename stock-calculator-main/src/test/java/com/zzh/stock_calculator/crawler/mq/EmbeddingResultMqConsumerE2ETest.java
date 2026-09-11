package com.zzh.stock_calculator.crawler.mq;

import com.zzh.stockcalc.contract.MessageEnvelope;
import com.zzh.stockcalc.contract.MessageType;
import com.zzh.stockcalc.contract.MqExchange;
import com.zzh.stockcalc.contract.MqKey;
import com.zzh.stockcalc.contract.MqQueue;
import com.zzh.stockcalc.contract.message.EmbeddingComputeResult;
import com.zzh.stockcalc.contract.message.EmbeddingComputeTask;
import com.zzh.stock_calculator.crawler.embedding.entity.ClsArticleEmbedding;
import com.zzh.stock_calculator.crawler.embedding.entity.EmbeddingStatus;
import com.zzh.stock_calculator.crawler.embedding.repository.ClsArticleEmbeddingRepository;
import com.zzh.stock_calculator.crawler.entity.ClsArticle;
import com.zzh.stock_calculator.crawler.repository.ClsArticleRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 阶段 3 验收 E2E（docs/data-service-split-design.md §8 阶段 3）：
 * 真实链路 result.embedding.done → RabbitMQ → 主服务消费 → 确定性 UUID 向量 upsert
 * + 状态行 DONE；重复投递无副作用（向量行不重复、指纹跳过、无死信）。
 * <p>依赖本地 PostgreSQL + RabbitMQ（docker compose），用 RABBIT_E2E=true 显式开启；
 * embedding 关闭避免测试上下文触发真实 CF 向量化（向量由测试直接伪造）；
 * crawler 关闭避免测试上下文启动真实拉取。测试自备 vector_store 表（与
 * PgVectorStore 同 DDL，幂等创建），避免依赖其他上下文已建表。</p>
 */
@EnabledIfEnvironmentVariable(named = "RABBIT_E2E", matches = "true")
@TestPropertySource(properties = {
        "datasvc.mq.enabled=true",
        "embedding.enabled=false",
        "crawler.enabled=false"
})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class EmbeddingResultMqConsumerE2ETest {

    /** 与真实数据隔离的测试锚点（勿用真实文章 id；与 ingest E2E 错开） */
    private static final long TEST_ARTICLE_ID = 990000000002L;
    private static final String TEST_CONTENT = "E2E 向量结果消费测试正文";
    private static final int DIMS = 1024;

    @Autowired
    private RabbitTemplate rabbitTemplate;
    @Autowired
    private AmqpAdmin amqpAdmin;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private ClsArticleRepository clsArticleRepository;
    @Autowired
    private ClsArticleEmbeddingRepository embeddingRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void ingestOnceAndIdempotentOnDuplicate() throws Exception {
        ensureVectorStoreTable();
        cleanRows();
        try {
            // 直接落测试文章（聚焦 embedding 结果消费链路，文章入库已有专属 E2E）
            clsArticleRepository.save(ClsArticle.builder()
                    .id(TEST_ARTICLE_ID).type(1).title("【标题】E2E向量结果消费")
                    .content(TEST_CONTENT).brief("阶段 3 验收").ctime(1757480000L)
                    .author("test").level("C").build());

            EmbeddingComputeResult payload = result();
            publish(payload);
            publish(payload); // 重复投递：验收「重复消费无副作用」

            assertTrue(await(() -> embeddingRepository.findById(TEST_ARTICLE_ID)
                            .map(r -> r.getStatus() == EmbeddingStatus.DONE).orElse(false)),
                    "10s 内未消费落账，检查 RabbitMQ/监听器");
            Thread.sleep(2000); // 留出重复消息的消费时间

            String expectedHash = sha256Hex(TEST_CONTENT);
            String expectedDocId = UUID.nameUUIDFromBytes(
                    ("cls-article:" + TEST_ARTICLE_ID).getBytes(StandardCharsets.UTF_8)).toString();

            ClsArticleEmbedding row = embeddingRepository.findById(TEST_ARTICLE_ID).orElseThrow();
            assertEquals(EmbeddingStatus.DONE, row.getStatus());
            assertEquals(expectedHash, row.getContentHash(), "contentHash 应为当前文章文本指纹");
            assertEquals(0, row.getFailCount());
            assertNotNull(row.getEmbeddedAt());
            assertEquals("@cf/baai/bge-m3", row.getModel());

            Integer vectorRows = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM public.vector_store WHERE id = ?::uuid",
                    Integer.class, expectedDocId);
            assertEquals(1, vectorRows, "确定性 UUID 向量行必须恰好一行");
            Integer dims = jdbcTemplate.queryForObject(
                    "SELECT vector_dims(embedding) FROM public.vector_store WHERE id = ?::uuid",
                    Integer.class, expectedDocId);
            assertEquals(DIMS, dims);
            String metadata = jdbcTemplate.queryForObject(
                    "SELECT metadata::text FROM public.vector_store WHERE id = ?::uuid",
                    String.class, expectedDocId);
            assertTrue(metadata.contains(String.valueOf(TEST_ARTICLE_ID)), "metadata 应含 articleId");

            Long deadDepth = queueDepth(MqQueue.DEAD);
            assertEquals(0L, deadDepth == null ? -1L : deadDepth, "正常消费不应产生死信");
        } finally {
            cleanRows();
        }
        assertFalse(embeddingRepository.existsById(TEST_ARTICLE_ID));
    }

    // ==================== 辅助 ====================

    private EmbeddingComputeResult result() {
        List<Float> vector = new ArrayList<>(DIMS);
        for (int i = 0; i < DIMS; i++) {
            vector.add(0.01f);
        }
        return EmbeddingComputeResult.builder()
                .kind(EmbeddingComputeTask.KIND_CLS_ARTICLE)
                .refId(TEST_ARTICLE_ID)
                .model("@cf/baai/bge-m3")
                .dims(DIMS)
                .vector(vector)
                .tokensUsed(115L)
                .build();
    }

    private void publish(EmbeddingComputeResult payload) {
        MessageEnvelope envelope = MessageEnvelope.builder()
                .messageId(UUID.randomUUID().toString())
                .type(MessageType.RESULT_EMBEDDING_DONE)
                .schemaVersion(MessageEnvelope.CURRENT_SCHEMA_VERSION)
                .occurredAt(OffsetDateTime.now().toInstant().toEpochMilli())
                .traceId(UUID.randomUUID().toString())
                .producer("e2e-test")
                .payload(payload)
                .build();
        String json = objectMapper.writeValueAsString(envelope);
        MessageProperties props = new MessageProperties();
        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        props.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        rabbitTemplate.send(MqExchange.RESULTS, MqKey.RESULT_EMBEDDING_DONE,
                new Message(json.getBytes(StandardCharsets.UTF_8), props));
    }

    /** 与 PgVectorStore 同 DDL 的幂等建表（测试自足，不依赖其他上下文已建表） */
    private void ensureVectorStoreTable() {
        jdbcTemplate.execute("CREATE EXTENSION IF NOT EXISTS vector");
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS public.vector_store (
                    id uuid PRIMARY KEY,
                    content text,
                    metadata json,
                    embedding vector(1024)
                )
                """);
    }

    private boolean await(Condition condition) throws InterruptedException {
        for (int i = 0; i < 40; i++) {
            if (condition.check()) {
                return true;
            }
            Thread.sleep(250);
        }
        return false;
    }

    @FunctionalInterface
    private interface Condition {
        boolean check();
    }

    private Long queueDepth(String queueName) {
        org.springframework.amqp.core.QueueInformation info = amqpAdmin.getQueueInfo(queueName);
        return info == null ? null : info.getMessageCount();
    }

    private void cleanRows() {
        jdbcTemplate.update("DELETE FROM public.vector_store WHERE id = ?::uuid",
                UUID.nameUUIDFromBytes(("cls-article:" + TEST_ARTICLE_ID)
                        .getBytes(StandardCharsets.UTF_8)).toString());
        embeddingRepository.findById(TEST_ARTICLE_ID).ifPresent(embeddingRepository::delete);
        clsArticleRepository.findById(TEST_ARTICLE_ID).ifPresent(clsArticleRepository::delete);
    }

    private static String sha256Hex(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
