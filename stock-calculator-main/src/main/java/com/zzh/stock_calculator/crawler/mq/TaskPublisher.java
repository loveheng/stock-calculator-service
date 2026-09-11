package com.zzh.stock_calculator.crawler.mq;

import com.zzh.stockcalc.contract.MessageEnvelope;
import com.zzh.stockcalc.contract.MqExchange;
import com.zzh.stockcalc.contract.MqPolicy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * 任务下行发布器（设计文档 §4.3/§4.4）：主服务 → worker/collector，
 * payload 包进统一信封序列化为 JSON 文本，routing key = 消息 type。
 * 持久化消息 + correlated confirms（CorrelationData=messageId）；
 * 确认 nack/退回只记错误不阻塞主流程，由对账重发兜底（D6）。
 * <p>阶段 3 服务向量化任务与历史补录触发；阶段 4 公告域复用时若涉及跨域引用，
 * 依 Modulith 规则上提至共享顶层包。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "datasvc.mq", name = "enabled", havingValue = "true")
public class TaskPublisher {

    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;

    /**
     * 发布任务消息到 stockcalc.tasks（worker 竞争消费 / collector 单发单收）。
     * @param type MessageType.TASK_*，同时作为 routing key
     */
    public void dispatchTask(String type, Object payload) {
        publish(MqExchange.TASKS, type, payload);
    }

    /**
     * 发布控制消息到 stockcalc.control（collector 单发单收，快照类覆盖式处理）。
     * @param type MessageType.CONTROL_*，同时作为 routing key
     */
    public void dispatchControl(String type, Object payload) {
        publish(MqExchange.CONTROL, type, payload);
    }

    private void publish(String exchange, String type, Object payload) {
        MessageEnvelope envelope = MessageEnvelope.builder()
                .messageId(UUID.randomUUID().toString())
                .type(type)
                .schemaVersion(MessageEnvelope.CURRENT_SCHEMA_VERSION)
                .occurredAt(System.currentTimeMillis())
                .traceId(UUID.randomUUID().toString())
                .producer(MqPolicy.PRODUCER_MAIN)
                .payload(payload)
                .build();
        String json = objectMapper.writeValueAsString(envelope);

        MessageProperties props = new MessageProperties();
        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        props.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        props.setMessageId(envelope.getMessageId());
        props.setType(type);
        props.setAppId(envelope.getProducer());

        // yml 已开 publisher-returns → mandatory 生效，不可达退回报错（对账兜底信号）
        rabbitTemplate.send(exchange, type,
                new Message(json.getBytes(StandardCharsets.UTF_8), props),
                new CorrelationData(envelope.getMessageId()));
        log.info("dispatched type={} exchange={} messageId={}", type, exchange, envelope.getMessageId());
    }
}
