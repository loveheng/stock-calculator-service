package com.zzh.stock_calculator.notify.mq;

import com.zzh.stock_calculator.notify.entity.ReminderEntity;
import com.zzh.stock_calculator.notify.repository.ReminderRepository;
import com.zzh.stockcalc.contract.MessageEnvelope;
import com.zzh.stockcalc.contract.MqQueue;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * on_event 事件提醒消费者（docs/notify/design.md §五）：reminder.event.q 绑定具体事件
 * key（如 result.announcement.done），事件即钟——无种子无自循环。
 * 链路：扫 active on_event 提醒（eventType 匹配）→ trigger_spec.filter 与事件 payload
 * 轻量等值匹配（JSONB 条件不引表达式引擎，§五）→ N7 限幅 → 委托
 * {@link ReminderActionExecutor} 分发动作；一次性事件提醒触发后置 done（无种子不续）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReminderEventConsumer {

    private final ReminderRepository reminderRepository;
    private final ReminderActionExecutor actionExecutor;
    private final ObjectMapper objectMapper;

    /** 消费全程一个事务：triggerOccupy/markSuppressed 是 @Modifying 更新，缺事务抛 TransactionRequiredException */
    @org.springframework.transaction.annotation.Transactional
    @RabbitListener(queues = MqQueue.REMINDER_EVENT)
    public void onEvent(String envelopeJson) {
        MessageEnvelope envelope;
        try {
            envelope = objectMapper.readValue(envelopeJson, MessageEnvelope.class);
        } catch (Exception e) {
            log.warn("[notify] 事件信封解析失败，跳过", e);
            return;
        }
        Map<String, Object> eventMap;
        try {
            eventMap = objectMapper.convertValue(envelope.getPayload(), Map.class);
        } catch (Exception e) {
            log.warn("[notify] 事件 payload 还原失败 type={}，跳过", envelope.getType(), e);
            return;
        }
        JsonNode event = objectMapper.valueToTree(eventMap);

        List<ReminderEntity> actives =
                reminderRepository.findByStatusAndTriggerType("active", "on_event");
        for (ReminderEntity reminder : actives) {
            if (!matches(reminder, envelope.getType(), event)) {
                continue;
            }
            // N7 运行期限幅：冷静期内命中只计数不触发
            if (reminder.getSuppressUntil() != null
                    && reminder.getSuppressUntil().isAfter(OffsetDateTime.now())) {
                reminderRepository.markSuppressed(reminder.getId());
                continue;
            }
            // 事件提醒无种子：幂等占位仅刷新 fired_at/fire_count（一次事件至多命中一次）
            reminderRepository.triggerOccupy(reminder.getId(), OffsetDateTime.now());
            actionExecutor.execute(reminder);
            // 事件提醒一次性语义：触发即完结（重复型语义属 at_time，on_event 重新登记即续）
            reminder.setStatus("done");
            reminder.setUpdatedAt(OffsetDateTime.now());
            reminderRepository.save(reminder);
            log.info("[notify] on_event 提醒触发 reminderId={} eventType={}",
                    reminder.getId(), envelope.getType());
        }
    }

    /** 轻量匹配（§五）：eventType 精确匹配 + filter 每个键在事件 payload 中等值命中 */
    private boolean matches(ReminderEntity reminder, String eventType, JsonNode event) {
        JsonNode spec;
        try {
            spec = objectMapper.readTree(reminder.getTriggerSpec());
        } catch (Exception e) {
            log.warn("[notify] reminder id={} trigger_spec 非法，跳过", reminder.getId());
            return false;
        }
        String specEvent = spec.path("eventType").asText();
        if (!specEvent.isEmpty() && !specEvent.equals(eventType)) {
            return false;
        }
        JsonNode filter = spec.path("filter");
        if (filter.isObject() && !filter.isEmpty()) {
            // filter 多键 AND 语义：任一键不命中即不匹配（值按文本等值比较）
            for (Map.Entry<String, JsonNode> entry : filter.properties()) {
                if (!event.path(entry.getKey()).asText()
                        .equals(entry.getValue().asText())) {
                    return false;
                }
            }
        }
        return true;
    }
}
