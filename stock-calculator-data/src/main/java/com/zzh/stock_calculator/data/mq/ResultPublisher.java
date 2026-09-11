package com.zzh.stock_calculator.data.mq;

import com.zzh.stockcalc.contract.MessageEnvelope;
import com.zzh.stockcalc.contract.MqExchange;
import com.zzh.stockcalc.contract.MqPolicy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * 结果上行发布器（设计文档 §4.3）：payload 包进统一信封序列化为 JSON 文本，
 * routing key = 消息 type（两侧排障对齐）。持久化消息 + confirms 由 yml/回调兜底。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ResultPublisher {

    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;

    public void publish(String type, Object payload) {
        publish(type, payload, MqPolicy.PRODUCER_COLLECTOR, UUID.randomUUID().toString());
    }

    /**
     * 全参发布（阶段 3 任务 5）：worker 消费端回传结果时以真实角色标识区分上行方，
     * 并透传任务信封的 traceId（跨服务排障串联：task → result 同链路可查）。
     */
    public void publish(String type, Object payload, String producer, String traceId) {
        MessageEnvelope envelope = MessageEnvelope.builder()
                .messageId(UUID.randomUUID().toString())
                .type(type)
                .schemaVersion(MessageEnvelope.CURRENT_SCHEMA_VERSION)
                .occurredAt(System.currentTimeMillis())
                .traceId(traceId)
                .producer(producer)
                .payload(payload)
                .build();
        String json = objectMapper.writeValueAsString(envelope);

        MessageProperties props = new MessageProperties();
        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        props.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        props.setMessageId(envelope.getMessageId());
        props.setType(type);
        props.setAppId(envelope.getProducer());

        rabbitTemplate.send(MqExchange.RESULTS, type, new Message(json.getBytes(StandardCharsets.UTF_8), props));
        log.debug("published result type={} messageId={}", type, envelope.getMessageId());
    }
}
