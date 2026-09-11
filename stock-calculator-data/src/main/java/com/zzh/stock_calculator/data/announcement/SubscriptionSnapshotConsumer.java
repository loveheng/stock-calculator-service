package com.zzh.stock_calculator.data.announcement;

import com.rabbitmq.client.Channel;
import com.zzh.stockcalc.contract.MessageEnvelope;
import com.zzh.stockcalc.contract.MessageType;
import com.zzh.stockcalc.contract.MqQueue;
import com.zzh.stockcalc.contract.message.SubscriptionSnapshotPayload;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;

/**
 * 控制面消费端（设计文档 §4.3/R3，§8 阶段 4 任务 2）：单发单收 collector.control.q，
 * control.subscription.snapshot → SubscriptionSnapshotCache 覆盖式更新。
 * <p>控制队列无重试环（快照丢失由 30min 定时重推兜底）：解析失败/未知 type 一律
 * 记日志后 ack 丢弃，防毒消息重投风暴；ack 前置异常不外抛（手动 ack 工厂）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "datasvc.collector", name = "enabled", havingValue = "true")
public class SubscriptionSnapshotConsumer {

    private final SubscriptionSnapshotCache snapshotCache;
    private final ObjectMapper objectMapper;

    @RabbitListener(queues = MqQueue.COLLECTOR_CONTROL,
            containerFactory = "collectorControlListenerFactory")
    public void onMessage(Message message,
                          Channel channel,
                          @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) {
        String body = new String(message.getBody(), StandardCharsets.UTF_8);
        try {
            dispatch(objectMapper.readValue(body, MessageEnvelope.class));
        } catch (Exception e) {
            // 毒消息不进重试环：控制面语义为「下一次快照自愈」（R6）
            log.error("control message unparsable, dropped, body={}",
                    body.length() <= 200 ? body : body.substring(0, 200) + "...", e);
        } finally {
            try {
                channel.basicAck(deliveryTag, false);
            } catch (Exception ackError) {
                log.error("failed to ack control message, broker will redeliver", ackError);
            }
        }
    }

    private void dispatch(MessageEnvelope envelope) {
        String type = envelope.getType() == null ? "" : envelope.getType();
        if (!MessageType.CONTROL_SUBSCRIPTION_SNAPSHOT.equals(type)) {
            log.info("skip unsupported control type={} messageId={}", envelope.getType(), envelope.getMessageId());
            return;
        }
        SubscriptionSnapshotPayload payload =
                objectMapper.convertValue(envelope.getPayload(), SubscriptionSnapshotPayload.class);
        boolean accepted = payload != null
                && snapshotCache.update(payload.getVersion(), payload.getStocks());
        if (accepted) {
            log.info("subscription snapshot applied version={} stocks={}",
                    payload.getVersion(), payload.getStocks() == null ? 0 : payload.getStocks().size());
        } else {
            log.warn("stale subscription snapshot rejected version={} (current={})",
                    payload == null ? null : payload.getVersion(), snapshotCache.get().version());
        }
    }
}
