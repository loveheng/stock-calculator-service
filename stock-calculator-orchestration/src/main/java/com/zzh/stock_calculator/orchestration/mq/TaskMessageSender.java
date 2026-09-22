package com.zzh.stock_calculator.orchestration.mq;

import com.zzh.stockcalc.contract.MessageEnvelope;
import com.zzh.stockcalc.contract.MqExchange;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 任务消息发送网关（步 6-2 mq_send 节点的底层通道）：统一走 TASKS topic 交换机，
 * MessageEnvelope 信封契约与 main/data 链路一致（D9）。
 * <p>重放幂等（§八 定案）：messageId 固定用 traceId——同一实例断点重放再发同键消息，
 * 消费端按 messageId 幂等去重，不重复触发业务。
 * <p>脱敏口径（memory 定案）：payload 只携带 correlationId（== traceId）与业务参数，
 * 不含 userId 等身份字段。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaskMessageSender {

    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper om = new ObjectMapper();

    /** 下发任务消息。 */
    public String send(String routingKey, String traceId, JsonNode payload) {
        doSend(routingKey, traceId, payload);
        log.info("[orchestration] mq_send routing={} messageId={} exchange={}", routingKey, traceId, MqExchange.TASKS);
        return traceId;
    }

    /** 任务启动请求（步 6-3a）：dispatch 即刻返回后经 MQ 触发消费侧执行，payload 仅带 taskId */
    public void sendRunRequest(long taskId, String traceId) {
        ObjectNode payload = om.createObjectNode().put("task_id", taskId);
        doSend(com.zzh.stockcalc.contract.MqKey.TASK_ORCHESTRATION_RUN, traceId, payload);
        log.info("[orchestration] 任务启动请求已下发 taskId={} traceId={}", taskId, traceId);
    }

    /**
     * 统一发送（body=信封 JSON 字符串）：无 Jackson MessageConverter 装配（与 main/data
     * 链路一致，消费端 ObjectMapper.readValue 解析），POJO 直发会走 SimpleMessageConverter
     * JDK 序列化导致消费端反序列化失败——序列化形态必须显式收敛。
     */
    private void doSend(String routingKey, String traceId, JsonNode payload) {
        String body = om.writeValueAsString(buildEnvelope(routingKey, traceId, payload));
        rabbitTemplate.convertAndSend(MqExchange.TASKS, routingKey, body);
    }

    private MessageEnvelope buildEnvelope(String routingKey, String traceId, JsonNode payload) {
        ObjectNode body = om.createObjectNode();
        body.put("correlation_id", traceId);
        if (payload instanceof ObjectNode p) {
            body.setAll(p);
        }
        return MessageEnvelope.builder()
                .messageId(traceId)
                .type(routingKey)
                .schemaVersion(MessageEnvelope.CURRENT_SCHEMA_VERSION)
                .occurredAt(System.currentTimeMillis())
                .traceId(traceId)
                .producer("stockcalc-orchestration")
                .payload(body)
                .build();
    }
}
