package com.zzh.stock_calculator.notify.mq;

import com.zzh.stockcalc.contract.MessageEnvelope;
import com.zzh.stockcalc.contract.MqExchange;
import com.zzh.stockcalc.contract.MqKey;
import com.zzh.stockcalc.contract.MqPolicy;
import com.zzh.stockcalc.contract.message.NotifyCapabilityTask;
import com.zzh.stockcalc.contract.message.NotifyPushPayload;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * notify 的 MQ 发布出口（design.md §四/§六）：
 * - notify.push：触达统一出口（N5），main push 消费者转 SSE/Web Push；
 * - task.notify.capability：能力请求直通（action.kind=capability）。
 * 消息体走 MessageEnvelope，traceId 进信封（agent-orchestration D9）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotifyPublisher {

    private final RabbitTemplate rabbitTemplate;
    private final tools.jackson.databind.ObjectMapper objectMapper;

    /** 组装后的通知投递（触达出口收敛一处，notify 不直连用户） */
    public void publishPush(NotifyPushPayload payload, String traceId) {
        publish(MqExchange.RESULTS, MqKey.NOTIFY_PUSH, payload, traceId);
    }

    /** 能力请求直通（main 能力消费者执行后沿 result.notify.capability 回流） */
    public void publishCapabilityTask(NotifyCapabilityTask payload, String traceId) {
        publish(MqExchange.TASKS, MqKey.TASK_NOTIFY_CAPABILITY, payload, traceId);
    }

    private void publish(String exchange, String key, Object payload, String traceId) {
        MessageEnvelope envelope = MessageEnvelope.builder()
                .messageId(UUID.randomUUID().toString())
                .type(key)
                .schemaVersion(MessageEnvelope.CURRENT_SCHEMA_VERSION)
                .occurredAt(System.currentTimeMillis())
                .traceId(traceId)
                .producer("stock-mcp-notify")
                .payload(payload)
                .build();
        // SimpleMessageConverter 不支持非 Serializable POJO：信封序列化为 JSON 直发
        // （data 侧 ResultPublisher 同款惯例），消费端 @RabbitListener 以 String 接收反序列化
        String json = objectMapper.writeValueAsString(envelope);
        org.springframework.amqp.core.MessageProperties props =
                new org.springframework.amqp.core.MessageProperties();
        props.setContentType(org.springframework.amqp.core.MessageProperties.CONTENT_TYPE_JSON);
        rabbitTemplate.send(exchange, key,
                new org.springframework.amqp.core.Message(
                        json.getBytes(java.nio.charset.StandardCharsets.UTF_8), props));
        log.info("[notify] published {} traceId={}", key, traceId);
    }
}
