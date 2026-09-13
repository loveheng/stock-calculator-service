package com.zzh.stock_calculator.data.hello;

import com.rabbitmq.client.Channel;
import com.zzh.stockcalc.contract.MessageType;
import com.zzh.stockcalc.contract.MqKey;
import com.zzh.stockcalc.contract.message.PullHeartbeatPayload;
import com.zzh.stock_calculator.data.mq.ResultPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

/**
 * HelloWorldConsumer 单测（docs/pull-loop-unification-design.md §8.3.4）：一次性消费
 * 形态——执行 + 心跳回报（appliedTtlMs=0 哨兵，不变量 8）+ 恒 ack；心跳失败不影响 ack。
 */
@ExtendWith(MockitoExtension.class)
class HelloWorldConsumerTest {

    @Mock
    private ResultPublisher resultPublisher;
    @Mock
    private Channel channel;

    private HelloWorldConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new HelloWorldConsumer(resultPublisher);
    }

    private Message seedMessage() {
        return new Message("seed".getBytes(), new MessageProperties());
    }

    @Test
    @DisplayName("消费一轮：打印执行 + 心跳回报（appliedTtlMs=0 哨兵）+ 恒 ack")
    void consumesTaskReportsHeartbeatAndAcks() throws Exception {
        consumer.onTask(seedMessage(), channel, 1L);

        ArgumentCaptor<PullHeartbeatPayload> payload = ArgumentCaptor.forClass(PullHeartbeatPayload.class);
        verify(resultPublisher).publish(eq(MessageType.RESULT_PULL_HEARTBEAT), payload.capture());
        assertEquals(MqKey.TASK_HELLO_WORLD, payload.getValue().getTaskCode());
        assertEquals(0, payload.getValue().getDepth());
        assertEquals(0L, payload.getValue().getAppliedTtlMs());
        verify(channel).basicAck(1L, false);
    }

    @Test
    @DisplayName("心跳回报异常被吞掉：不影响手动 ack（观测信号非控制信号）")
    void heartbeatFailureStillAcks() throws Exception {
        doThrow(new RuntimeException("broker down")).when(resultPublisher)
                .publish(eq(MessageType.RESULT_PULL_HEARTBEAT), any(PullHeartbeatPayload.class));

        consumer.onTask(seedMessage(), channel, 2L);

        verify(channel).basicAck(2L, false);
    }

    @Test
    @DisplayName("心跳 payload 时间戳为当次执行时刻")
    void heartbeatCarriesExecutionTime() throws Exception {
        long before = System.currentTimeMillis();
        consumer.onTask(seedMessage(), channel, 3L);

        ArgumentCaptor<PullHeartbeatPayload> payload = ArgumentCaptor.forClass(PullHeartbeatPayload.class);
        verify(resultPublisher).publish(eq(MessageType.RESULT_PULL_HEARTBEAT), payload.capture());
        assertTrue(payload.getValue().getRenewedAt() >= before);
    }
}
