package com.zzh.stock_calculator.crawler.mq;

import com.zzh.stockcalc.contract.MessageEnvelope;
import com.zzh.stockcalc.contract.MqExchange;
import com.zzh.stockcalc.contract.MqPolicy;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

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

    /**
     * 发布自循环种子到延迟队列（docs/architecture/pull-loop-unification.md，L1）：
     * 无信封（内部循环消息，消费端不解析载荷），routing key = delay 队列绑定 key，
     * per-message expiration 逐条携带（per-queue x-message-ttl 声明期不可变，L5）。
     * 幂等性由 data 侧深度守卫保证（补多不炸）。
     */
    public void dispatchSeed(String delayKey, long ttlMs) {
        MessageProperties props = new MessageProperties();
        props.setContentType(MessageProperties.CONTENT_TYPE_TEXT_PLAIN);
        props.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        props.setExpiration(String.valueOf(ttlMs));
        rabbitTemplate.send(
            MqExchange.TASKS,
            delayKey,
            new Message("seed".getBytes(StandardCharsets.UTF_8), props),
            new CorrelationData(UUID.randomUUID().toString())
        );
        log.info(
            "dispatched pull-loop seed delayKey={} ttl={}ms",
            delayKey,
            ttlMs
        );
    }

    /**
     * 发布带 payload 的延迟任务（copilot 记忆种子等：TTL 到期经 DLX 改写后消费端需要载荷）。
     * 与 dispatchSeed 同为 per-message expiration；与 dispatchTask 同为信封 JSON。
     *
     * <p>routingKey 与 envelopeType 必须分开传：DLX 到期改写只换 routing key 不改 body，
     * 而消费端按 envelope.type 路由——信封 type 必须写「改写后的目标类型」（如 tick key），
     * 写 delay key 自身会导致消息到期后落进消费端 default 分支被静默丢弃。
     */
    public void dispatchDelayedTask(
        String routingKey,
        String envelopeType,
        Object payload,
        long ttlMs
    ) {
        MessageEnvelope envelope = MessageEnvelope.builder()
            .messageId(UUID.randomUUID().toString())
            .type(envelopeType)
            .schemaVersion(MessageEnvelope.CURRENT_SCHEMA_VERSION)
            .occurredAt(System.currentTimeMillis())
            .traceId(UUID.randomUUID().toString())
            .producer(MqPolicy.PRODUCER_MAIN)
            .payload(payload)
            .build();
        String json = objectMapper.writeValueAsString(envelope);
        MessageProperties props = buildProps(
            envelopeType,
            envelope.getMessageId()
        );
        props.setExpiration(String.valueOf(ttlMs));
        send(
            MqExchange.TASKS,
            routingKey,
            json,
            props,
            envelope.getMessageId()
        );
        log.info(
            "dispatched delayed routingKey={} envelopeType={} ttl={}ms messageId={}",
            routingKey,
            envelopeType,
            ttlMs,
            envelope.getMessageId()
        );
    }

    /**
     * 发布日历任务（docs/architecture/pull-loop-unification.md §8，L8）：种子同款裸消息直发
     * 工作队列（无 TTL、无信封，消费端不解析载荷）；投递资格由看门狗 CAS 认领保证（L12）。
     */
    public void dispatchCalendarTask(String taskKey) {
        MessageProperties props = new MessageProperties();
        props.setContentType(MessageProperties.CONTENT_TYPE_TEXT_PLAIN);
        props.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        rabbitTemplate.send(
            MqExchange.TASKS,
            taskKey,
            new Message("seed".getBytes(StandardCharsets.UTF_8), props),
            new CorrelationData(UUID.randomUUID().toString())
        );
        log.info("dispatched calendar task taskKey={}", taskKey);
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

        send(
            exchange,
            type,
            json,
            buildProps(type, envelope.getMessageId()),
            envelope.getMessageId()
        );
        log.info(
            "dispatched type={} exchange={} messageId={}",
            type,
            exchange,
            envelope.getMessageId()
        );
    }

    private MessageProperties buildProps(String type, String messageId) {
        MessageProperties props = new MessageProperties();
        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        props.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        if (messageId != null) {
            props.setMessageId(messageId);
        }
        props.setType(type);
        props.setAppId(MqPolicy.PRODUCER_MAIN);
        return props;
    }

    private void send(
        String exchange,
        String key,
        String json,
        MessageProperties props,
        String correlationId
    ) {
        // yml 已开 publisher-returns → mandatory 生效，不可达退回报错（对账兑底信号）
        rabbitTemplate.send(
            exchange,
            key,
            new Message(json.getBytes(StandardCharsets.UTF_8), props),
            new CorrelationData(correlationId)
        );
    }
}
