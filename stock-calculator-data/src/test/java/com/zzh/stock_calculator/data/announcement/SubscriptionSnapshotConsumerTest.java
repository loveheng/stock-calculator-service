package com.zzh.stock_calculator.data.announcement;

import com.rabbitmq.client.Channel;
import com.zzh.stockcalc.contract.MessageEnvelope;
import com.zzh.stockcalc.contract.MessageType;
import com.zzh.stockcalc.contract.message.SubscriptionSnapshotPayload;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * SubscriptionSnapshotConsumer 单测：快照分发给缓存、未知 type 跳过、
 * 毒消息吞掉不抛（控制面无重试环）、全部路径 ack。
 */
@ExtendWith(MockitoExtension.class)
class SubscriptionSnapshotConsumerTest {

    @Mock
    private SubscriptionSnapshotCache snapshotCache;
    @Mock
    private Channel channel;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private SubscriptionSnapshotConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new SubscriptionSnapshotConsumer(snapshotCache, objectMapper);
    }

    private Message messageOf(String json) {
        return new Message(json.getBytes(StandardCharsets.UTF_8), new MessageProperties());
    }

    private String snapshotJson(long version, String stockId) throws Exception {
        MessageEnvelope envelope = MessageEnvelope.builder()
                .messageId("m-1").type(MessageType.CONTROL_SUBSCRIPTION_SNAPSHOT)
                .schemaVersion(MessageEnvelope.CURRENT_SCHEMA_VERSION)
                .occurredAt(System.currentTimeMillis()).traceId("t-1").producer("e2e-test")
                .payload(SubscriptionSnapshotPayload.builder().version(version)
                        .stocks(List.of(SubscriptionSnapshotPayload.SnapshotStock.builder()
                                .stockId(stockId).orgId("org").since(null).build()))
                        .build())
                .build();
        return objectMapper.writeValueAsString(envelope);
    }

    @Test
    @DisplayName("快照消息 → 缓存更新（version + stocks 透传）")
    void snapshotDispatchedToCache() throws Exception {
        when(snapshotCache.update(anyLong(), any())).thenReturn(true);

        consumer.onMessage(messageOf(snapshotJson(100L, "600000")), channel, 1L);

        verify(snapshotCache).update(eq(100L), any());
        verify(channel).basicAck(eq(1L), eq(false));
    }

    @Test
    @DisplayName("未知 control type 跳过（不触缓存）仍 ack")
    void unknownTypeSkippedStillAcked() throws Exception {
        MessageEnvelope envelope = MessageEnvelope.builder()
                .messageId("m-2").type("control.unknown")
                .schemaVersion(MessageEnvelope.CURRENT_SCHEMA_VERSION)
                .occurredAt(System.currentTimeMillis()).traceId("t").producer("e2e-test")
                .payload("whatever").build();

        consumer.onMessage(messageOf(objectMapper.writeValueAsString(envelope)), channel, 2L);

        verifyNoInteractions(snapshotCache);
        verify(channel).basicAck(eq(2L), eq(false));
    }

    @Test
    @DisplayName("毒消息（非 JSON）吞掉不抛、不触缓存，仍 ack（控制面无重试环）")
    void unparsableBodySwallowedAndAcked() throws Exception {
        assertThatCode(() -> consumer.onMessage(messageOf("not-json{"), channel, 3L))
                .doesNotThrowAnyException();

        verifyNoInteractions(snapshotCache);
        verify(channel).basicAck(eq(3L), eq(false));
    }

    @Test
    @DisplayName("过期快照（缓存拒绝）仍 ack（由下一次快照兜底）")
    void staleSnapshotAcked() throws Exception {
        when(snapshotCache.update(anyLong(), any())).thenReturn(false);

        consumer.onMessage(messageOf(snapshotJson(1L, "600000")), channel, 4L);

        verify(snapshotCache).update(eq(1L), any());
        verify(channel).basicAck(eq(4L), eq(false));
    }
}
