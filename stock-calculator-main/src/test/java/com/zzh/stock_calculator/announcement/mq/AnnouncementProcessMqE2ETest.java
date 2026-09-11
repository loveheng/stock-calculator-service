package com.zzh.stock_calculator.announcement.mq;

import com.zzh.stockcalc.contract.MessageEnvelope;
import com.zzh.stockcalc.contract.MessageType;
import com.zzh.stockcalc.contract.MqExchange;
import com.zzh.stockcalc.contract.MqKey;
import com.zzh.stockcalc.contract.MqQueue;
import com.zzh.stockcalc.contract.message.AnnouncementDonePayload;
import com.zzh.stockcalc.contract.message.AnnouncementFailedPayload;
import com.zzh.stockcalc.contract.message.AnnouncementProcessTask;
import com.zzh.stockcalc.contract.message.EmbeddingComputeResult;
import com.zzh.stockcalc.contract.message.EmbeddingComputeTask;
import com.zzh.stockcalc.contract.message.SliceSelection;
import com.zzh.stockcalc.contract.message.StructureNode;
import com.zzh.stock_calculator.announcement.entity.Announcement;
import com.zzh.stock_calculator.announcement.entity.AnnouncementContent;
import com.zzh.stock_calculator.announcement.entity.AnnouncementFailReason;
import com.zzh.stock_calculator.announcement.entity.AnnouncementStatus;
import com.zzh.stock_calculator.announcement.repository.AnnouncementContentRepository;
import com.zzh.stock_calculator.announcement.repository.AnnouncementRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 阶段 4 任务 3 验收 E2E（docs/data-service-split-design.md §8 阶段 4）：
 * 主服务发布端 PENDING 扫描 → task.announcement.process 落队（元数据四件套）；
 * 测试扮演 worker 消费后回报 result.announcement.done → content 溯源行 upsert +
 * summary 落账 + 二段向量化任务自动下发（task.embedding.compute kind=announcement）；
 * 回报 result.embedding.done → 确定性 UUID 向量落库 + 状态 DONE（全链闭环）；
 * result.announcement.failed(PERMANENT) → FAILED 终态。正常链路无死信。
 * <p>依赖本地 PostgreSQL + RabbitMQ（docker compose），RABBIT_E2E=true 显式开启；
 * embedding.enabled=true 仅为二段下发功能开关（CF 凭据属 worker，主服务不需要）；
 * embedding.backfill.enabled=false + announcement.process.cron=- 关闭全部定时器：
 * 缓存上下文在类结束后仍存活，回填/调度扫真实库发布任务会污染共享 broker 测试队列；
 * 990003 段为测试锚点；收尾删除公告行/溯源行/向量行并清空任务队列。</p>
 */
@EnabledIfEnvironmentVariable(named = "RABBIT_E2E", matches = "true")
@TestPropertySource(properties = {
        "datasvc.mq.enabled=true",
        "embedding.enabled=true",
        "crawler.enabled=false",
        "announcement.process.enabled=true",
        "announcement.process.cron=-",
        "embedding.backfill.enabled=false"
})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class AnnouncementProcessMqE2ETest {

    private static final String TEST_ANN_ID = "e2e-ann-990000000004";
    private static final String TEST_ANN_ID_FAILED = TEST_ANN_ID + "-failed";
    private static final String TEST_STOCK_ID = "990003";

    @Autowired
    private RabbitTemplate rabbitTemplate;
    @Autowired
    private AmqpAdmin amqpAdmin;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private AnnouncementRepository announcementRepository;
    @Autowired
    private AnnouncementContentRepository contentRepository;
    @Autowired
    private AnnouncementProcessPublisher processPublisher;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void tearDown() {
        cleanRows();
        purgeWorkQueues();
    }

    @Test
    void processTaskPublishedAndDoneChainCloses() throws Exception {
        cleanRows();
        purgeWorkQueues();
        Announcement row = seedAnnouncement(TEST_ANN_ID);

        // ① 发布端：PENDING 扫描 → task.announcement.process 落队
        int dispatched = processPublisher.publishPendingBatch();
        assertTrue(dispatched >= 1, "发布端应至少下发 1 条处理任务");

        Message processMessage = receive(MqQueue.TASK_ANNOUNCEMENT_PROCESS);
        assertNotNull(processMessage, "task.announcement.process.q 应有处理任务消息");
        MessageEnvelope processEnvelope = readEnvelope(processMessage);
        assertEquals(MessageType.TASK_ANNOUNCEMENT_PROCESS, processEnvelope.getType());
        AnnouncementProcessTask processTask = objectMapper.convertValue(
                processEnvelope.getPayload(), AnnouncementProcessTask.class);
        assertEquals(TEST_ANN_ID, processTask.getAnnouncementId());
        assertEquals("u-e2e.pdf", processTask.getAdjunctUrl());
        assertEquals(TEST_STOCK_ID, processTask.getSecCode());

        // ② 扮演 worker：回报 result.announcement.done → content upsert + summary + 二段下发
        publishResult(MessageType.RESULT_ANNOUNCEMENT_DONE, MqKey.RESULT_ANNOUNCEMENT_DONE,
                donePayload());
        assertTrue(await(() -> {
            Announcement fresh = announcementRepository.findById(row.getId()).orElse(null);
            return fresh != null && "E2E 蒸馏摘要".equals(fresh.getSummary());
        }), "15s 内 done 未落账（summary 缺失）");
        assertTrue(await(() -> contentRepository.findById(row.getId()).isPresent()),
                "15s 内 content 溯源行未落库");
        AnnouncementContent content = contentRepository.findById(row.getId()).orElseThrow();
        assertEquals("pdfbox-e2e", content.getExtractorVersion());
        assertEquals(1200, content.getCharCount());
        assertNotNull(content.getStructureJson());

        // 二段：done 落账后自动下发 task.embedding.compute（kind=announcement, text=summary）
        Message embeddingTaskMessage = receive(MqQueue.TASK_EMBEDDING_COMPUTE);
        assertNotNull(embeddingTaskMessage, "done 落账后应自动下发二段向量化任务");
        MessageEnvelope embeddingEnvelope = readEnvelope(embeddingTaskMessage);
        assertEquals(MessageType.TASK_EMBEDDING_COMPUTE, embeddingEnvelope.getType());
        EmbeddingComputeTask embeddingTask = objectMapper.convertValue(
                embeddingEnvelope.getPayload(), EmbeddingComputeTask.class);
        assertEquals(EmbeddingComputeTask.KIND_ANNOUNCEMENT, embeddingTask.getKind());
        assertEquals(row.getId(), embeddingTask.getRefId());
        assertEquals("E2E 蒸馏摘要", embeddingTask.getText());

        // ③ 扮演 worker：回报 result.embedding.done → 向量落库 + 状态 DONE
        publishResult(MessageType.RESULT_EMBEDDING_DONE, MqKey.RESULT_EMBEDDING_DONE,
                embeddingDonePayload(row.getId()));
        assertTrue(await(() -> {
            Announcement fresh = announcementRepository.findById(row.getId()).orElse(null);
            return fresh != null && fresh.getStatus() == AnnouncementStatus.DONE;
        }), "15s 内向量结果未落账（status 未 DONE）");
        Integer vectorRows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM vector_store WHERE metadata->>'announcementId' = ?",
                Integer.class, String.valueOf(row.getId()));
        assertEquals(1, vectorRows, "确定性 UUID 向量行应存在且唯一");

        assertEquals(0L, deadDepth(), "正常链路不应产生死信");
    }

    @Test
    void failedReportGoesTerminal() throws Exception {
        cleanRows();
        purgeWorkQueues();
        seedAnnouncement(TEST_ANN_ID_FAILED);

        publishResult(MessageType.RESULT_ANNOUNCEMENT_FAILED, MqKey.RESULT_ANNOUNCEMENT_FAILED,
                AnnouncementFailedPayload.builder()
                        .announcementId(TEST_ANN_ID_FAILED)
                        .failReason(AnnouncementFailReason.SKIPPED_NO_TEXT.name())
                        .errorKind(AnnouncementFailedPayload.ERROR_KIND_PERMANENT)
                        .message("扫描件")
                        .build());

        assertTrue(await(() -> {
            List<Announcement> found = announcementRepository
                    .findByAnnouncementIdIn(List.of(TEST_ANN_ID_FAILED));
            return !found.isEmpty() && found.get(0).getStatus() == AnnouncementStatus.FAILED;
        }), "15s 内 failed 回报未落终态");
        Announcement saved = announcementRepository
                .findByAnnouncementIdIn(List.of(TEST_ANN_ID_FAILED)).get(0);
        assertEquals(AnnouncementFailReason.SKIPPED_NO_TEXT, saved.getStatusReason());

        assertEquals(0L, deadDepth(), "正常链路不应产生死信");
    }

    // ==================== 辅助 ====================

    private Announcement seedAnnouncement(String announcementId) {
        Announcement row = Announcement.builder()
                .announcementId(announcementId)
                .title("E2E 处理测试公告")
                .adjunctUrl("u-e2e.pdf")
                .seDate(java.time.LocalDate.of(2026, 9, 10))
                .secCode(TEST_STOCK_ID)
                .secName("E2E处理股票")
                .status(AnnouncementStatus.PENDING)
                .failCount(0)
                .build();
        return announcementRepository.save(row);
    }

    private AnnouncementDonePayload donePayload() {
        return AnnouncementDonePayload.builder()
                .announcementId(TEST_ANN_ID)
                .extractorVersion("pdfbox-e2e")
                .charCount(1200)
                .pageCount(3)
                .content("E2E 全文文本（主服务不落正文，D5）")
                .summary("E2E 蒸馏摘要")
                .structure(List.of(StructureNode.builder()
                        .nodeId("0").title("根").level(0).startOffset(0).endOffset(10)
                        .build()))
                .selection(SliceSelection.builder().promptVersion("e2e-v1").build())
                .build();
    }

    private EmbeddingComputeResult embeddingDonePayload(Long announcementId) {
        List<Float> vector = new java.util.ArrayList<>(1024);
        for (int i = 0; i < 1024; i++) {
            vector.add(0.01f);
        }
        return EmbeddingComputeResult.builder()
                .kind(EmbeddingComputeTask.KIND_ANNOUNCEMENT)
                .refId(announcementId)
                .model("@cf/baai/bge-m3")
                .dims(1024)
                .vector(vector)
                .tokensUsed(115L)
                .build();
    }

    private void publishResult(String type, String routingKey, Object payload) {
        MessageEnvelope envelope = MessageEnvelope.builder()
                .messageId(UUID.randomUUID().toString())
                .type(type)
                .schemaVersion(MessageEnvelope.CURRENT_SCHEMA_VERSION)
                .occurredAt(System.currentTimeMillis())
                .traceId(UUID.randomUUID().toString())
                .producer("e2e-test-worker")
                .payload(payload)
                .build();
        String json = objectMapper.writeValueAsString(envelope);
        MessageProperties props = new MessageProperties();
        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        props.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        rabbitTemplate.send(MqExchange.RESULTS, routingKey,
                new Message(json.getBytes(StandardCharsets.UTF_8), props));
    }

    /** 从工作队列取一条消息（测试扮演 worker；超时返回 null） */
    private Message receive(String queue) {
        for (int i = 0; i < 40; i++) {
            Message message = rabbitTemplate.receive(queue, 250);
            if (message != null) {
                return message;
            }
        }
        return null;
    }

    private MessageEnvelope readEnvelope(Message message) {
        String body = new String(message.getBody(), StandardCharsets.UTF_8);
        return objectMapper.readValue(body, MessageEnvelope.class);
    }

    private boolean await(Condition condition) throws InterruptedException {
        for (int i = 0; i < 60; i++) {
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

    private long deadDepth() {
        QueueInformation info = amqpAdmin.getQueueInfo(MqQueue.DEAD);
        return info == null ? -1L : info.getMessageCount();
    }

    private void purgeWorkQueues() {
        amqpAdmin.purgeQueue(MqQueue.TASK_ANNOUNCEMENT_PROCESS, false);
        amqpAdmin.purgeQueue(MqQueue.TASK_EMBEDDING_COMPUTE, false);
    }

    /** 清理：公告行（content 行 FK 级联）+ 向量行 + 任务队列残留 */
    private void cleanRows() {
        List<Announcement> rows = announcementRepository.findByAnnouncementIdIn(
                List.of(TEST_ANN_ID, TEST_ANN_ID_FAILED));
        for (Announcement row : rows) {
            jdbcTemplate.update("DELETE FROM vector_store WHERE metadata->>'announcementId' = ?",
                    String.valueOf(row.getId()));
        }
        if (!rows.isEmpty()) {
            announcementRepository.deleteAll(rows);
        }
    }
}
