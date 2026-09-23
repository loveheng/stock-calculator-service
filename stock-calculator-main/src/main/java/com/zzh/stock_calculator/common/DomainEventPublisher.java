package com.zzh.stock_calculator.common;

import com.zzh.stockcalc.contract.MqExchange;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.Map;

/**
 * 业务领域事件发布器（agent-orchestration 领域事件化，P3）：业务事实发生后向
 * EVENTS 交换机（stockcalc.events，topic）发 event.* 消息，编排器 fan-in 消费唤醒
 * mq_wait 挂起实例——替代编排器轮询。与 task.* 指令（驱动 worker 干活）语义分离：
 * event 只陈述「已发生的事实」，无接收方也成立，投递后即忘。
 * <p>置顶 common 基包（Modulith：跨域只允许引用对方基包类型，common.mq 子包不可见）。
 * 事务安全：publishAfterCommit 注册 afterCommit 回调，事务回滚不发事件（防消费者读到
 * 未提交事实）；无事务时同步直发。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DomainEventPublisher {

    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper om = new ObjectMapper();

    /**
     * 事务提交后发布领域事件；无事务上下文时同步直发。
     *
     * @param routingKey 形如 event.announcement.done.600519（MqKey.EVENT_* 契约）
     * @param data       事件载荷（编排器 mq_wait.filter 匹配的事实字段，如 stock_id）
     */
    public void publishAfterCommit(String routingKey, Map<String, Object> data) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    doPublish(routingKey, data);
                }
            });
        } else {
            doPublish(routingKey, data);
        }
    }

    private void doPublish(String routingKey, Map<String, Object> data) {
        try {
            ObjectNode payload = om.createObjectNode();
            data.forEach((k, v) -> payload.putPOJO(k, v));
            // messageId 用 routingKey+时间戳（事件非幂等任务指令，消费端按匹配语义自幂等）
            String messageId = routingKey + "-" + System.currentTimeMillis();
            ObjectNode body = om.createObjectNode();
            body.setAll(payload);
            String json = om.writeValueAsString(com.zzh.stockcalc.contract.MessageEnvelope.builder()
                    .messageId(messageId)
                    .type(routingKey)
                    .schemaVersion(com.zzh.stockcalc.contract.MessageEnvelope.CURRENT_SCHEMA_VERSION)
                    .occurredAt(System.currentTimeMillis())
                    .producer("stockcalc-main")
                    .payload(body)
                    .build());
            rabbitTemplate.convertAndSend(MqExchange.EVENTS, routingKey, json);
            log.info("[domain-event] published routing={} keys={}", routingKey, data.keySet());
        } catch (RuntimeException e) {
            // 事件面零依赖：发布失败只告警，不回滚业务事务（编排器有 wait_deadline 超时兜底）
            log.warn("[domain-event] publish failed routing={}: {}", routingKey, e.getMessage());
        }
    }
}
