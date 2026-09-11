package com.zzh.stock_calculator.announcement.mq;

import com.zzh.stockcalc.contract.MessageEnvelope;
import com.zzh.stockcalc.contract.MessageType;
import com.zzh.stockcalc.contract.MqExchange;
import com.zzh.stockcalc.contract.MqKey;
import com.zzh.stockcalc.contract.MqPolicy;
import com.zzh.stockcalc.contract.MqQueue;
import com.zzh.stockcalc.contract.message.SubscriptionSnapshotPayload;
import com.zzh.stock_calculator.announcement.entity.AnnouncementSubscription;
import com.zzh.stock_calculator.announcement.repository.AnnouncementSubscriptionRepository;
import com.zzh.stock_calculator.announcement.service.AnnouncementSubscriptionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 阶段 4 任务 1 验收 E2E（docs/data-service-split-design.md §8 阶段 4）：
 * 真实链路 订阅快照 → stockcalc.control 交换机 → collector.control.q，
 * 信封与 payload 契约（version/stocks[{stockId,orgId,since}]）落队可还原；
 * unsubscribe 事务提交后 AFTER_COMMIT 触发重推（覆盖式语义：新快照不含已退订标的）。
 * <p>依赖本地 PostgreSQL + RabbitMQ（docker compose），用 RABBIT_E2E=true 显式开启；
 * collector.control.q 拓扑此处幂等声明（正式由数据服务侧 MqTopologyConfig 声明，
 * 参数严格一致），收尾 purge——任务 1 阶段 data 侧尚无消费者，队列残留须清零。
 * announcement_subscription.user_id 对 users 有 DB 外键，测试用户经 JdbcTemplate
 * 造数（避开跨域 Java 引用），990 段 UUID + 隔离 email，收尾删除。</p>
 */
@EnabledIfEnvironmentVariable(named = "RABBIT_E2E", matches = "true")
@TestPropertySource(properties = {
        "datasvc.mq.enabled=true",
        "embedding.enabled=false",
        "crawler.enabled=false"
})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class SubscriptionSnapshotMqE2ETest {

    /** 与真实数据隔离的测试锚点（990xxx 段，勿用真实股票代码） */
    private static final String TEST_STOCK_ID = "990001";
    private static final UUID TEST_USER_ID = UUID.fromString("00000000-0000-0000-0000-000000009901");
    private static final String TEST_USER_EMAIL = "e2e-snapshot-9901@test.local";

    @Autowired
    private RabbitTemplate rabbitTemplate;
    @Autowired
    private AmqpAdmin amqpAdmin;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private SubscriptionSnapshotPublisher snapshotPublisher;
    @Autowired
    private AnnouncementSubscriptionService subscriptionService;
    @Autowired
    private AnnouncementSubscriptionRepository subscriptionRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        // 控制面拓扑幂等声明（正式由数据服务声明；此处自持保证测试可独立跑通）
        amqpAdmin.declareExchange(new TopicExchange(MqExchange.CONTROL));
        amqpAdmin.declareQueue(new Queue(MqQueue.COLLECTOR_CONTROL, true));
        amqpAdmin.declareBinding(BindingBuilder.bind(new Queue(MqQueue.COLLECTOR_CONTROL))
                .to(new TopicExchange(MqExchange.CONTROL)).with(MqKey.BIND_CONTROL_ALL));
        purgeControlQueue();
        cleanTestRows();
        jdbcTemplate.update(
                "insert into users (id, email, password_hash) values (?, ?, ?)",
                TEST_USER_ID, TEST_USER_EMAIL, "$2a$10$e2eonlyfortestdummyhashdummyhashdummyhashdummyhash12");
    }

    @AfterEach
    void tearDown() {
        cleanTestRows();
        purgeControlQueue();
    }

    @Test
    void snapshotLandsInCollectorQueueWithContractPayload() throws Exception {
        subscriptionRepository.save(AnnouncementSubscription.builder()
                .userId(TEST_USER_ID).stockId(TEST_STOCK_ID).build());

        assertTrue(snapshotPublisher.publishSnapshot(), "MQ 已启用时快照必须投递成功");

        MessageEnvelope envelope = receiveEnvelope();
        assertEquals(MessageType.CONTROL_SUBSCRIPTION_SNAPSHOT, envelope.getType());
        assertEquals(MqPolicy.PRODUCER_MAIN, envelope.getProducer());

        SubscriptionSnapshotPayload payload =
                objectMapper.convertValue(envelope.getPayload(), SubscriptionSnapshotPayload.class);
        assertTrue(payload.getVersion() > 0, "version 必须为正（epoch millis）");
        SubscriptionSnapshotPayload.SnapshotStock testStock = payload.getStocks().stream()
                .filter(s -> TEST_STOCK_ID.equals(s.getStockId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("快照必须包含测试标的 " + TEST_STOCK_ID));
        // 无存量订阅行 orgId / 无存量公告 → since null（collector 按首拉配置推导）
        assertNull(testStock.getOrgId());
        assertNull(testStock.getSince());

        QueueInformation dead = amqpAdmin.getQueueInfo(MqQueue.DEAD);
        assertEquals(0L, dead == null ? -1L : dead.getMessageCount(), "正常下发不应产生死信");
    }

    @Test
    void unsubscribeCommitTriggersRepublishWithoutStock() throws Exception {
        subscriptionRepository.save(AnnouncementSubscription.builder()
                .userId(TEST_USER_ID).stockId(TEST_STOCK_ID).build());
        // 订阅行已就绪；purge 掉 setup 后一切残留（含启动首推），只验退订触发的新快照
        purgeControlQueue();

        assertTrue(subscriptionService.unsubscribe(TEST_USER_ID, TEST_STOCK_ID), "退订应成功");

        MessageEnvelope envelope = receiveEnvelope();
        assertEquals(MessageType.CONTROL_SUBSCRIPTION_SNAPSHOT, envelope.getType());
        SubscriptionSnapshotPayload payload =
                objectMapper.convertValue(envelope.getPayload(), SubscriptionSnapshotPayload.class);
        assertFalse(payload.getStocks().stream().anyMatch(s -> TEST_STOCK_ID.equals(s.getStockId())),
                "退订提交后的快照不得再含已退订标的（覆盖式语义）");
    }

    // ==================== 辅助 ====================

    private MessageEnvelope receiveEnvelope() throws InterruptedException {
        for (int i = 0; i < 40; i++) {
            Message message = rabbitTemplate.receive(MqQueue.COLLECTOR_CONTROL, 250);
            if (message != null) {
                String body = new String(message.getBody(), StandardCharsets.UTF_8);
                return objectMapper.readValue(body, MessageEnvelope.class);
            }
        }
        throw new AssertionError("10s 内未收到快照消息，检查交换机/绑定/publisher 门控");
    }

    private void purgeControlQueue() {
        amqpAdmin.purgeQueue(MqQueue.COLLECTOR_CONTROL, false);
    }

    /** 先删订阅行（FK 在子表）再删测试用户；幂等容忍残留 */
    private void cleanTestRows() {
        List<AnnouncementSubscription> rows = subscriptionRepository.findByStockId(TEST_STOCK_ID);
        if (!rows.isEmpty()) {
            subscriptionRepository.deleteAll(rows);
        }
        jdbcTemplate.update("delete from users where id = ?", TEST_USER_ID);
    }
}
