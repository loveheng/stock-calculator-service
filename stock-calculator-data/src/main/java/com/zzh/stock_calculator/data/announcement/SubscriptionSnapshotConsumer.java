package com.zzh.stock_calculator.data.announcement;

import com.rabbitmq.client.Channel;
import com.zzh.stockcalc.contract.MessageEnvelope;
import com.zzh.stockcalc.contract.MessageType;
import com.zzh.stockcalc.contract.MqExchange;
import com.zzh.stockcalc.contract.MqKey;
import com.zzh.stockcalc.contract.message.PullConfigPayload;
import com.zzh.stockcalc.contract.message.SubscriptionSnapshotPayload;
import com.zzh.stock_calculator.data.mq.PullConfigCache;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.ExchangeTypes;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
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
 * <p>控制面为每副本独占匿名队列（exclusive + auto-delete）绑 control.# 的广播语义：
 * 快照/拉取配置送达全副本，多副本无配置漂移；副本断开队列自动清理。
 * 控制队列无重试环（快照丢失由 30min 定时重推兜底）：解析失败/未知 type 一律
 * 记日志后 ack 丢弃，防毒消息重投风暴；ack 前置异常不外抛（手动 ack 工厂）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "datasvc.collector", name = "enabled", havingValue = "true")
public class SubscriptionSnapshotConsumer {

    private final SubscriptionSnapshotCache snapshotCache;
    private final PullConfigCache pullConfigCache;
    private final ObjectMapper objectMapper;

    /**
     * 声明式匿名队列（@Queue 空名 → 服务端命名、非持久、独占、auto-delete）绑
     * control.#：快照/拉取配置广播到每副本。不用 SpEL 引用队列 bean——native 下
     * SpEL 属性访问缺反射元数据（R2 冒烟实证，Expression parsing failed），
     * 注解属性一律用常量声明式表达。
     */
    @RabbitListener(bindings = @QueueBinding(
            value = @org.springframework.amqp.rabbit.annotation.Queue(
                    value = "", durable = "false", exclusive = "true", autoDelete = "true"),
            exchange = @Exchange(name = MqExchange.CONTROL, type = ExchangeTypes.TOPIC),
            key = MqKey.BIND_CONTROL_ALL),
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
        if (MessageType.CONTROL_PULL_CONFIG.equals(type)) {
            applyPullConfig(envelope);
            return;
        }
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

    /** control.pull.config → PullConfigCache 覆盖式更新（订阅快照同款语义，L4） */
    private void applyPullConfig(MessageEnvelope envelope) {
        PullConfigPayload payload =
                objectMapper.convertValue(envelope.getPayload(), PullConfigPayload.class);
        boolean accepted = payload != null
                && pullConfigCache.update(payload.getVersion(), payload.getTasks());
        if (accepted) {
            log.info("pull config applied version={} tasks={}",
                    payload.getVersion(), payload.getTasks() == null ? 0 : payload.getTasks().size());
        } else {
            log.warn("stale pull config rejected version={}",
                    payload == null ? null : payload.getVersion());
        }
    }
}
