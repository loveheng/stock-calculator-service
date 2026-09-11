package com.zzh.stock_calculator.announcement.mq;

import com.zzh.stockcalc.contract.MessageEnvelope;
import com.zzh.stockcalc.contract.MessageType;
import com.zzh.stockcalc.contract.MqExchange;
import com.zzh.stockcalc.contract.MqKey;
import com.zzh.stockcalc.contract.MqQueue;
import com.zzh.stockcalc.contract.message.AnnouncementCollectedPayload;
import com.zzh.stock_calculator.announcement.entity.Announcement;
import com.zzh.stock_calculator.announcement.entity.AnnouncementStatus;
import com.zzh.stock_calculator.announcement.entity.AnnouncementSubscription;
import com.zzh.stock_calculator.announcement.repository.AnnouncementRepository;
import com.zzh.stock_calculator.announcement.repository.AnnouncementSubscriptionRepository;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 阶段 4 任务 2 验收 E2E（docs/data-service-split-design.md §8 阶段 4）：
 * 真实链路 result.announcement.collected → RabbitMQ → 主服务消费 →
 * announcementId 幂等落 PENDING + orgId 回填订阅行（R3 对账）；
 * 重复投递无副作用（不翻倍、不覆盖），超体积直接终态 FAILED，正常消费无死信。
 * <p>依赖本地 PostgreSQL + RabbitMQ（docker compose），RABBIT_E2E=true 显式开启；
 * 990002/990000000003 为测试锚点段；测试用户经 JdbcTemplate 造数（users DB 外键），
 * 收尾删除；crawler 关闭避免测试上下文启动真实拉取。</p>
 */
@EnabledIfEnvironmentVariable(named = "RABBIT_E2E", matches = "true")
@TestPropertySource(properties = {
        "datasvc.mq.enabled=true",
        "embedding.enabled=false",
        "crawler.enabled=false"
})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class AnnouncementCollectedMqE2ETest {

    private static final String TEST_ANN_ID = "e2e-ann-990000000003";
    private static final String TEST_STOCK_ID = "990002";
    private static final UUID TEST_USER_ID = UUID.fromString("00000000-0000-0000-0000-000000009902");
    private static final String TEST_USER_EMAIL = "e2e-collected-9902@test.local";
    private static final String TEST_ORG_ID = "ge2e0990002";

    @Autowired
    private RabbitTemplate rabbitTemplate;
    @Autowired
    private AmqpAdmin amqpAdmin;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private AnnouncementRepository announcementRepository;
    @Autowired
    private AnnouncementSubscriptionRepository subscriptionRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void tearDown() {
        cleanRows();
    }

    @Test
    void ingestCollectedAndDedupOnDuplicate() throws Exception {
        cleanRows();
        seedUserAndSubscription();

        publishCollected(payload(TEST_ORG_ID));
        publishCollected(payload(TEST_ORG_ID)); // 重复投递：验收「重复投递无副作用」

        AtomicReference<Announcement> row = new AtomicReference<>();
        assertTrue(await(() -> {
            List<Announcement> found = announcementRepository
                    .findByAnnouncementIdIn(List.of(TEST_ANN_ID));
            if (!found.isEmpty()) {
                row.set(found.get(0));
                return true;
            }
            return false;
        }), "15s 内未消费入库，检查 RabbitMQ/监听器");
        Thread.sleep(2000); // 留出重复消息的消费时间

        Announcement saved = row.get();
        assertEquals(AnnouncementStatus.PENDING, saved.getStatus());
        assertEquals("E2E 采集测试公告", saved.getTitle());
        assertEquals("2026-09-10", saved.getSeDate() == null ? null : saved.getSeDate().toString());
        assertEquals(TEST_STOCK_ID, saved.getSecCode());
        assertEquals(TEST_ORG_ID, orgIdOfSubscription(), "orgId 应回填订阅行（R3 对账）");

        assertEquals(0L, deadDepth(), "正常消费不应产生死信");
    }

    @Test
    void oversizeCollectedGoesTerminalFailed() throws Exception {
        cleanRows();
        seedUserAndSubscription();

        publishCollected(payload(TEST_ORG_ID).toBuilder()
                .announcementId(TEST_ANN_ID + "-big")
                .adjunctSize(60 * 1024L) // 60MB > 50MB 上限
                .build());

        assertTrue(await(() -> !announcementRepository
                        .findByAnnouncementIdIn(List.of(TEST_ANN_ID + "-big")).isEmpty()),
                "15s 内未消费超体积公告，检查 RabbitMQ/监听器");

        Announcement saved = announcementRepository
                .findByAnnouncementIdIn(List.of(TEST_ANN_ID + "-big")).get(0);
        assertEquals(AnnouncementStatus.FAILED, saved.getStatus(),
                "超体积公告应直接终态 FAILED（DOWNLOAD_FAIL），不进处理队列");

        assertEquals(0L, deadDepth(), "正常消费不应产生死信");
    }

    // ==================== 辅助 ====================

    private AnnouncementCollectedPayload payload(String orgId) {
        return AnnouncementCollectedPayload.builder()
                .announcementId(TEST_ANN_ID)
                .title("E2E 采集测试公告")
                .adjunctUrl("finalpage/2026-09-10/e2e-ann-990000000003.pdf")
                .seDate("2026-09-10")
                .secCode(TEST_STOCK_ID)
                .secName("E2E测试股票")
                .adjunctSize(123L)
                .orgId(orgId)
                .build();
    }

    private void publishCollected(AnnouncementCollectedPayload payload) {
        MessageEnvelope envelope = MessageEnvelope.builder()
                .messageId(UUID.randomUUID().toString())
                .type(MessageType.RESULT_ANNOUNCEMENT_COLLECTED)
                .schemaVersion(MessageEnvelope.CURRENT_SCHEMA_VERSION)
                .occurredAt(System.currentTimeMillis())
                .traceId(UUID.randomUUID().toString())
                .producer("e2e-test")
                .payload(payload)
                .build();
        String json = objectMapper.writeValueAsString(envelope);
        MessageProperties props = new MessageProperties();
        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        props.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        rabbitTemplate.send(MqExchange.RESULTS, MqKey.RESULT_ANNOUNCEMENT_COLLECTED,
                new Message(json.getBytes(StandardCharsets.UTF_8), props));
    }

    private void seedUserAndSubscription() {
        jdbcTemplate.update("insert into users (id, email, password_hash) values (?, ?, ?)",
                TEST_USER_ID, TEST_USER_EMAIL,
                "$2a$10$e2eonlyfortestdummyhashdummyhashdummyhashdummyhash12");
        subscriptionRepository.save(AnnouncementSubscription.builder()
                .userId(TEST_USER_ID).stockId(TEST_STOCK_ID).build());
    }

    private String orgIdOfSubscription() {
        List<AnnouncementSubscription> rows = subscriptionRepository.findByStockId(TEST_STOCK_ID);
        return rows.isEmpty() ? null : rows.get(0).getOrgId();
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

    /** 清理测试数据（订阅行 FK 在 users，先删订阅再删用户；公告行按 id 删） */
    private void cleanRows() {
        List<Announcement> rows = announcementRepository.findByAnnouncementIdIn(
                List.of(TEST_ANN_ID, TEST_ANN_ID + "-big"));
        if (!rows.isEmpty()) {
            announcementRepository.deleteAll(rows);
        }
        List<AnnouncementSubscription> subRows = subscriptionRepository.findByStockId(TEST_STOCK_ID);
        if (!subRows.isEmpty()) {
            subscriptionRepository.deleteAll(subRows);
        }
        jdbcTemplate.update("delete from users where id = ?", TEST_USER_ID);
    }
}
