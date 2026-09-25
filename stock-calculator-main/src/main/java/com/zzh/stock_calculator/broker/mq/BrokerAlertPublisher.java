package com.zzh.stock_calculator.broker.mq;

import com.zzh.stockcalc.contract.MessageEnvelope;
import com.zzh.stockcalc.contract.MqExchange;
import com.zzh.stockcalc.contract.MqKey;
import com.zzh.stockcalc.contract.message.NotifyPushPayload;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * broker 监控告警发布出口（free-canvas §3.5·B 调度路径）：直投 notify.push 队列，
 * 复用既有 NotifyPushMqConsumer → Web Push 落库+推送全链（N5 触达出口收敛一处，
 * broker 不直连用户）。序列化惯例与 mcp-notify NotifyPublisher 同款（JSON 信封直发）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BrokerAlertPublisher {

    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;

    public void publishPush(NotifyPushPayload payload, String traceId) {
        MessageEnvelope envelope = MessageEnvelope.builder()
                .messageId(UUID.randomUUID().toString())
                .type(MqKey.NOTIFY_PUSH)
                .schemaVersion(MessageEnvelope.CURRENT_SCHEMA_VERSION)
                .occurredAt(System.currentTimeMillis())
                .traceId(traceId)
                .producer("stock-calculator-main")
                .payload(payload)
                .build();
        String json = objectMapper.writeValueAsString(envelope);
        MessageProperties props = new MessageProperties();
        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        rabbitTemplate.send(MqExchange.RESULTS, MqKey.NOTIFY_PUSH,
                new Message(json.getBytes(StandardCharsets.UTF_8), props));
        log.info("[broker-monitor] published notify.push traceId={}", traceId);
    }
}
