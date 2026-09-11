package com.zzh.stock_calculator.data.mq;

import com.zzh.stockcalc.contract.MessageType;
import com.zzh.stockcalc.contract.MqQueue;
import com.zzh.stockcalc.contract.message.ClsArticleDto;
import com.zzh.stockcalc.contract.message.ClsArticlePayload;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 拓扑 + 发布链路验证（需本地 RabbitMQ，docker compose rabbitmq 服务）：
 * 数据服务启动即声明全量拓扑；publish 后消息必须落在 result.ingest.q。
 * 使用与真实数据无关的唯一测试 id，收尾清空队列。
 * collector.enabled=false：测试上下文不装配 ClsPullTask，避免定时器真实打 CLS API。
 */
@TestPropertySource(properties = "datasvc.collector.enabled=false")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ResultPublisherTopologyTest {

    private static final long TEST_ARTICLE_ID = 990000000002L;

    @Autowired
    private ResultPublisher resultPublisher;

    @Autowired
    private AmqpAdmin amqpAdmin;

    @Test
    void publishLandsInResultIngestQueue() {
        ClsArticlePayload payload = ClsArticlePayload.builder()
                .article(ClsArticleDto.builder()
                        .id(TEST_ARTICLE_ID)
                        .type(1)
                        .title("拓扑验证测试消息")
                        .ctime(1757480000L)
                        .author("test")
                        .level("C")
                        .build())
                .build();

        resultPublisher.publish(MessageType.RESULT_CLS_ARTICLE, payload);

        Long messageCount = queueDepth(MqQueue.RESULT_INGEST);
        assertNotNull(messageCount, "result.ingest.q 应已由拓扑声明创建");
        assertTrue(messageCount >= 1, "发布后 result.ingest.q 应至少有 1 条消息，实际=" + messageCount);

        amqpAdmin.purgeQueue(MqQueue.RESULT_INGEST, false);
    }

    private Long queueDepth(String queueName) {
        QueueInformation info = amqpAdmin.getQueueInfo(queueName);
        return info == null ? null : info.getMessageCount();
    }
}
