package com.zzh.stock_calculator.crawler.mq;

import com.zzh.stockcalc.contract.MessageType;
import com.zzh.stockcalc.contract.MqQueue;
import com.zzh.stockcalc.contract.message.EmbeddingComputeTask;
import com.zzh.stockcalc.contract.message.HistorySyncTask;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 任务下发链路验证（需本地 RabbitMQ，且数据服务侧拓扑已声明——
 * 主服务按设计不声明 task 队列，仅发布）：dispatchTask 后消息必须落在
 * 对应 task 队列（embedding → worker 竞争队列；history.sync → collector 单发单收）。
 * 收尾清空队列，不产生业务副作用。
 * <p>datasvc.mq.enabled=true 会同时装配 ClsArticleMqConsumer（监听 result.ingest.q），
 * 本测试不发布 result 消息，互不干扰。</p>
 */
@EnabledIfEnvironmentVariable(named = "RABBIT_E2E", matches = "true")
@TestPropertySource(properties = {
        "embedding.enabled=false"
})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class TaskPublisherTopologyTest {

    private static final long TEST_REF_ID = 990000000101L;

    @Autowired
    private TaskPublisher taskPublisher;

    @Autowired
    private AmqpAdmin amqpAdmin;

    @Test
    void dispatchTaskLandsInCorrespondingQueues() {
        taskPublisher.dispatchTask(MessageType.TASK_EMBEDDING_COMPUTE,
                EmbeddingComputeTask.builder()
                        .kind(EmbeddingComputeTask.KIND_CLS_ARTICLE)
                        .refId(TEST_REF_ID)
                        .text("任务下发链路验证文本")
                        .build());
        taskPublisher.dispatchTask(MessageType.TASK_HISTORY_SYNC,
                HistorySyncTask.builder()
                        .requestId("e2e-history-1")
                        .source("cls")
                        .startTime(1757480000L)
                        .endTime(1757483600L)
                        .build());

        assertQueueDepth(MqQueue.TASK_EMBEDDING_COMPUTE, 1);
        assertQueueDepth(MqQueue.TASK_HISTORY_SYNC, 1);
    }

    private void assertQueueDepth(String queueName, int expected) {
        QueueInformation info = amqpAdmin.getQueueInfo(queueName);
        assertNotNull(info, queueName + " 应已由数据服务拓扑声明创建");
        assertTrue(info.getMessageCount() >= expected,
                queueName + " 应至少有 " + expected + " 条消息，实际=" + info.getMessageCount());
        amqpAdmin.purgeQueue(queueName, false);
        QueueInformation after = amqpAdmin.getQueueInfo(queueName);
        assertEquals(0L, after == null ? -1L : after.getMessageCount(), queueName + " 清空后应为 0");
    }
}
