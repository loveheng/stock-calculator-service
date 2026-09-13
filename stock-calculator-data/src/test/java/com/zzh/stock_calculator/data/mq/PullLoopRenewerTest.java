package com.zzh.stock_calculator.data.mq;

import com.rabbitmq.client.AMQP;
import com.zzh.stockcalc.contract.MessageType;
import com.zzh.stockcalc.contract.MqExchange;
import com.zzh.stockcalc.contract.MqKey;
import com.zzh.stockcalc.contract.MqQueue;
import com.zzh.stockcalc.contract.message.PullHeartbeatPayload;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.ChannelCallback;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PullLoopRenewer 单测（docs/pull-loop-unification-design.md §3.3 / L2）：深度守卫
 * 三路分支——正常续种（expiration 逐条携带）、深度>0 跳过、disabled 跳过；心跳回报
 * 是观测信号，任何分支都不上抛。
 */
@ExtendWith(MockitoExtension.class)
class PullLoopRenewerTest {

    @Mock
    private RabbitTemplate rabbitTemplate;
    @Mock
    private ResultPublisher resultPublisher;

    private PullConfigCache configCache;
    private PullLoopRenewer renewer;

    @BeforeEach
    void setUp() {
        configCache = new PullConfigCache();
        renewer = new PullLoopRenewer(rabbitTemplate, resultPublisher, configCache);
    }

    private AMQP.Queue.DeclareOk declareOkWithCount(int count) {
        AMQP.Queue.DeclareOk declareOk = mock(AMQP.Queue.DeclareOk.class);
        when(declareOk.getMessageCount()).thenReturn(count);
        return declareOk;
    }

    @Test
    @DisplayName("深度=0：正常续种，expiration 写入消息属性，心跳 depth=0")
    void renewsWithPerMessageExpiration() {
        doReturn(declareOkWithCount(0)).when(rabbitTemplate)
                .execute(ArgumentMatchers.<ChannelCallback<AMQP.Queue.DeclareOk>>any());

        renewer.renew(MqKey.TASK_CLS_PULL, MqQueue.TASK_CLS_PULL_DELAY,
                MqKey.TASK_CLS_PULL_DELAY, true, 480_000L);

        ArgumentCaptor<MessagePostProcessor> processor =
                ArgumentCaptor.forClass(MessagePostProcessor.class);
        verify(rabbitTemplate).convertAndSend(eq(MqExchange.TASKS),
                eq(MqKey.TASK_CLS_PULL_DELAY), eq("seed"), processor.capture());
        Message stamped = processor.getValue().postProcessMessage(
                new Message("seed".getBytes(StandardCharsets.UTF_8), new MessageProperties()));
        assertEquals("480000", stamped.getMessageProperties().getExpiration(),
                "per-message expiration 必须逐条携带（L5：per-queue TTL 不可变）");

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(resultPublisher).publish(eq(MessageType.RESULT_PULL_HEARTBEAT), payload.capture());
        PullHeartbeatPayload heartbeat = (PullHeartbeatPayload) payload.getValue();
        assertEquals(0, heartbeat.getDepth());
        assertEquals(480_000L, heartbeat.getAppliedTtlMs());
    }

    @Test
    @DisplayName("深度>0：守卫跳过续种（防双种子），心跳如实上报")
    void skipsRenewalWhenDelayQueueNotEmpty() {
        doReturn(declareOkWithCount(1)).when(rabbitTemplate)
                .execute(ArgumentMatchers.<ChannelCallback<AMQP.Queue.DeclareOk>>any());

        renewer.renew(MqKey.TASK_CLS_PULL, MqQueue.TASK_CLS_PULL_DELAY,
                MqKey.TASK_CLS_PULL_DELAY, true, 480_000L);

        verify(rabbitTemplate, never()).convertAndSend(anyString(), anyString(), anyString(),
                any(MessagePostProcessor.class));
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(resultPublisher).publish(eq(MessageType.RESULT_PULL_HEARTBEAT), payload.capture());
        assertEquals(1, ((PullHeartbeatPayload) payload.getValue()).getDepth());
    }

    @Test
    @DisplayName("disabled：不探针不续种，心跳 appliedTtl=0")
    void disabledSkipsEverything() {
        configCache.update(1L, java.util.List.of(
                com.zzh.stockcalc.contract.message.PullConfigPayload.TaskConfig.builder()
                        .taskCode(MqKey.TASK_CLS_PULL).enabled(false).ttlMs(0L).build()));

        renewer.renew(MqKey.TASK_CLS_PULL, MqQueue.TASK_CLS_PULL_DELAY,
                MqKey.TASK_CLS_PULL_DELAY, true, 480_000L);

        verify(rabbitTemplate, never()).execute(ArgumentMatchers.<ChannelCallback<AMQP.Queue.DeclareOk>>any());
        verify(rabbitTemplate, never()).convertAndSend(anyString(), anyString(), anyString(),
                any(MessagePostProcessor.class));
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(resultPublisher).publish(eq(MessageType.RESULT_PULL_HEARTBEAT), payload.capture());
        assertEquals(0, ((PullHeartbeatPayload) payload.getValue()).getAppliedTtlMs(),
                "跳过续种时 appliedTtl 应为 0");
    }
}
