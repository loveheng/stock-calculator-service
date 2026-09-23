package com.zzh.stock_calculator.orchestration.mq;

import com.zzh.stockcalc.contract.MqExchange;
import com.zzh.stockcalc.contract.MessageEnvelope;
import com.zzh.stockcalc.contract.MqKey;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 领域事件 fan-in 监听器（agent-orchestration 领域事件化，P3）：消费 main 发布的业务领域
 * 事件（event.*，EVENTS 交换机），匹配语义与唤醒续跑全部在 {@link MqWaitWakeService}——
 * mq_wait 唤醒键从「队列/trace_id 回调」升级为事件语义。
 * <p>一个 fan-in 队列不按用户建队列。事件无匹配挂起实例时落 domain_event_inbox 收件箱
 * （P3+：实例挂起落定即重放，治愈「事件先于挂起到达即丢」）；匹配失败实例最终由
 * MqWaitTimeoutScanner（超时兜底）收口。编排器除超时扫描与保留清理外零轮询。</p>
 * <p>与 TaskResultEventListener（task.completed.* 按 trace_id 精确回调）职责分离：
 * 本类只处理 DAG 节点声明了 event 的 mq_wait（{@code {event:"announcement.done", filter:{...},
 * timeout_seconds:N}}）；旧式 mq_wait（无 event 字段）不受影响。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DomainEventFaninListener {

    private final MqWaitWakeService mqWaitWakeService;

    private final ObjectMapper om = new ObjectMapper();

    @RabbitListener(bindings = @org.springframework.amqp.rabbit.annotation.QueueBinding(
            value = @org.springframework.amqp.rabbit.annotation.Queue(
                    value = "orchestration.domain.event.q",
                    durable = "true",
                    arguments = @org.springframework.amqp.rabbit.annotation.Argument(
                            name = "x-expires", value = "86400000", type = "java.lang.Long")),
            exchange = @org.springframework.amqp.rabbit.annotation.Exchange(
                    value = MqExchange.EVENTS, type = "topic"),
            key = MqKey.EVENT_PREFIX + "#"))
    public void onDomainEvent(String body) {
        MessageEnvelope envelope = parse(body);
        String routing = envelope.getType();
        if (routing == null || !routing.startsWith(MqKey.EVENT_PREFIX)) {
            log.warn("[orchestration] 非法领域事件 routing={}，忽略", routing);
            return;
        }
        JsonNode payload = om.valueToTree(envelope.getPayload());
        mqWaitWakeService.onDomainEvent(routing, payload);
    }

    private MessageEnvelope parse(String body) {
        try {
            return om.readValue(body, MessageEnvelope.class);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("信封解析失败: " + e.getMessage(), e);
        }
    }
}
