package com.zzh.stock_calculator.crawler.mq;

import com.zzh.stockcalc.contract.MessageEnvelope;
import com.zzh.stockcalc.contract.MessageType;
import com.zzh.stockcalc.contract.MqExchange;
import com.zzh.stockcalc.contract.MqKey;
import com.zzh.stockcalc.contract.MqQueue;
import com.zzh.stockcalc.contract.message.ClsArticleDto;
import com.zzh.stockcalc.contract.message.ClsArticlePayload;
import com.zzh.stockcalc.contract.message.ClsStockDict;
import com.zzh.stockcalc.contract.message.ClsStockLink;
import com.zzh.stockcalc.contract.message.ClsSubjectDict;
import com.zzh.stockcalc.contract.message.ClsSubjectLink;
import com.zzh.stock_calculator.crawler.repository.ClsArticleRepository;
import com.zzh.stock_calculator.crawler.repository.ClsArticleStockRepository;
import com.zzh.stock_calculator.crawler.repository.ClsArticleSubjectRepository;
import com.zzh.stock_calculator.crawler.repository.ClsSubjectRepository;
import com.zzh.stock_calculator.crawler.repository.StockRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 阶段 1 验收 E2E（docs/data-service-split-design.md §8 阶段 1）：
 * 真实链路 result.cls.article → RabbitMQ → 主服务消费 → 幂等入库；
 * 重复投递无副作用（关联不翻倍、无死信）。
 * <p>依赖本地 PostgreSQL + RabbitMQ（docker compose），用 RABBIT_E2E=true 显式开启
 * （默认套件自动跳过）；embedding 关闭避免测试文章触发真实 CF 向量化；
 * crawler 关闭避免测试上下文启动真实拉取。</p>
 */
@EnabledIfEnvironmentVariable(named = "RABBIT_E2E", matches = "true")
@TestPropertySource(properties = {
        "datasvc.mq.enabled=true",
        "embedding.enabled=false",
        "crawler.enabled=false"
})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ClsArticleMqConsumerE2ETest {

    /** 与真实数据隔离的测试锚点（勿用真实文章 id） */
    private static final long TEST_ARTICLE_ID = 990000000001L;
    private static final String TEST_STOCK_ID = "SZ990001";
    private static final long TEST_SUBJECT_ID = 999999001L;

    @Autowired
    private RabbitTemplate rabbitTemplate;
    @Autowired
    private AmqpAdmin amqpAdmin;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private ClsArticleRepository clsArticleRepository;
    @Autowired
    private ClsArticleSubjectRepository clsArticleSubjectRepository;
    @Autowired
    private ClsArticleStockRepository clsArticleStockRepository;
    @Autowired
    private StockRepository stockRepository;
    @Autowired
    private ClsSubjectRepository clsSubjectRepository;

    @Test
    void ingestOnceAndDedupOnDuplicate() throws Exception {
        boolean stockExistedBefore = stockRepository.existsById(TEST_STOCK_ID);
        boolean subjectExistedBefore = clsSubjectRepository.existsById(TEST_SUBJECT_ID);
        cleanRows();

        ClsArticlePayload payload = fullPayload();
        publish(payload);
        publish(payload); // 重复投递：验收「重复投递无副作用」

        assertTrue(await(() -> clsArticleRepository.existsById(TEST_ARTICLE_ID)),
                "10s 内未消费入库，检查 RabbitMQ/监听器");
        Thread.sleep(2000); // 留出重复消息的消费时间

        assertTrue(clsArticleRepository.existsById(TEST_ARTICLE_ID));
        assertEquals(1, clsArticleSubjectRepository.findByArticleIdIn(List.of(TEST_ARTICLE_ID)).size(),
                "文章-题材关联不得因重复投递翻倍");
        assertEquals(1, clsArticleStockRepository.findByArticleIdIn(List.of(TEST_ARTICLE_ID)).size(),
                "文章-股票关联不得因重复投递翻倍");
        Long deadDepth = queueDepth(MqQueue.DEAD);
        assertEquals(0L, deadDepth == null ? -1L : deadDepth, "正常消费不应产生死信");

        cleanRows();
        if (!stockExistedBefore) {
            stockRepository.deleteById(TEST_STOCK_ID);
        }
        if (!subjectExistedBefore) {
            clsSubjectRepository.deleteById(TEST_SUBJECT_ID);
        }
        assertFalse(clsArticleRepository.existsById(TEST_ARTICLE_ID));
    }

    // ==================== 辅助 ====================

    private ClsArticlePayload fullPayload() {
        return ClsArticlePayload.builder()
                .article(ClsArticleDto.builder()
                        .id(TEST_ARTICLE_ID)
                        .type(1)
                        .title("E2E 幂等入库测试文章")
                        .brief("阶段 1 验收")
                        .content("内容略")
                        .ctime(1757480000L)
                        .author("test")
                        .level("C")
                        .build())
                .subjectDicts(List.of(ClsSubjectDict.builder()
                        .subjectId(TEST_SUBJECT_ID).subjectName("E2E测试题材").build()))
                .stockDicts(List.of(ClsStockDict.builder()
                        .stockId(TEST_STOCK_ID).name("E2E测试股票").oldName("E2E测试股票").isStib(false).build()))
                .subjectLinks(List.of(ClsSubjectLink.builder()
                        .articleId(TEST_ARTICLE_ID).subjectId(TEST_SUBJECT_ID).build()))
                .stockLinks(List.of(ClsStockLink.builder()
                        .articleId(TEST_ARTICLE_ID).stockId(TEST_STOCK_ID).build()))
                .build();
    }

    private void publish(ClsArticlePayload payload) {
        MessageEnvelope envelope = MessageEnvelope.builder()
                .messageId(UUID.randomUUID().toString())
                .type(MessageType.RESULT_CLS_ARTICLE)
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
        rabbitTemplate.send(MqExchange.RESULTS, MqKey.RESULT_CLS_ARTICLE,
                new Message(json.getBytes(StandardCharsets.UTF_8), props));
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

    /** 清理测试文章与关联（字典行由存在性检查决定是否删除，避免误删真实数据） */
    private void cleanRows() {
        clsArticleStockRepository.deleteAll(
                clsArticleStockRepository.findByArticleIdIn(List.of(TEST_ARTICLE_ID)));
        clsArticleSubjectRepository.deleteAll(
                clsArticleSubjectRepository.findByArticleIdIn(List.of(TEST_ARTICLE_ID)));
        clsArticleRepository.findById(TEST_ARTICLE_ID).ifPresent(clsArticleRepository::delete);
    }
}
