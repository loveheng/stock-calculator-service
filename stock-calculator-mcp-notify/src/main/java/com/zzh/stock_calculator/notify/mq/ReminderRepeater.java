package com.zzh.stock_calculator.notify.mq;

import com.zzh.stock_calculator.notify.entity.ReminderEntity;
import com.zzh.stock_calculator.notify.repository.ReminderRepository;
import com.zzh.stockcalc.contract.MqQueue;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;

/**
 * 重复型提醒自我续种（docs/notify/design.md §五-3）：
 * daily/weekly 在触发后把下一周期 TTL 编进新种子（钟摆自循环）；once 一次性置 done 不续。
 * 深度守卫（§五-4，同 pull-loop §4 先例）：续种前 passive declare 探 delay 队列
 * messageCount，超阈值判重复种子堆积，放弃本粒度续种由幂等占位兜底。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReminderRepeater {

    /** delay 队列深度上限：超出即判种子堆积（正常一个 at_time 提醒在途种子应为 1） */
    static final long MAX_DELAY_DEPTH = 1000;

    private final ReminderRepository reminderRepository;
    private final ReminderSeeder reminderSeeder;
    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;

    /** 触发后续种或完结：due 为本次触发时刻（下一周期锚点） */
    public void reseedOrComplete(ReminderEntity reminder, OffsetDateTime due) {
        if (!"at_time".equals(reminder.getTriggerType())) {
            return;
        }
        String repeat;
        try {
            JsonNode spec = objectMapper.readTree(reminder.getTriggerSpec());
            repeat = spec.path("repeat").asText("once");
        } catch (Exception e) {
            log.error("[notify] reminder id={} spec JSON 非法，按 once 完结", reminder.getId(), e);
            repeat = "once";
        }

        long nextMs = switch (repeat) {
            case "daily" -> ChronoUnit.DAYS.getDuration().toMillis();
            case "weekly" -> ChronoUnit.WEEKS.getDuration().toMillis();
            default -> 0;
        };
        if (nextMs <= 0) {
            // capability 动作不在 fire 时完结：回流尚未到达，置 done 会让回流按
            // 「reminder 非活跃」丢弃、通知永远发不出——完结移交回流投递后执行
            if (!isCapabilityAction(reminder)) {
                complete(reminder.getId());
            }
            return;
        }

        if (delayQueueDepth() > MAX_DELAY_DEPTH) {
            // 深度守卫：堆积时放弃续种；既有在途种子到期仍会触发（幂等占位挡重复）
            log.warn("[notify] delay 队列深度超限（{}），reminder id={} 本轮放弃续种",
                    MAX_DELAY_DEPTH, reminder.getId());
            return;
        }

        OffsetDateTime nextFireAt = due.plus(nextMs, ChronoUnit.MILLIS);
        reminderSeeder.seed(reminder.getId(), nextFireAt);
        // 定向更新：不用陈旧实体整行 save（会覆盖 triggerOccupy 刚写的 fired_at/fire_count）
        reminderRepository.markStatus(reminder.getId(), reminder.getStatus());
        log.info("[notify] reminder id={} 续种至 {}", reminder.getId(), nextFireAt);
    }

    /** 一次性提醒触发后置 done（定向更新，防整行 save 丢失更新） */
    private void complete(Long reminderId) {
        reminderRepository.markStatus(reminderId, "done");
        log.info("[notify] reminder id={} 一次性触发完成，置 done", reminderId);
    }

    /** action.kind=capability 判定（完结时点移交流回侧） */
    private boolean isCapabilityAction(ReminderEntity reminder) {
        try {
            return "capability".equals(objectMapper.readTree(reminder.getAction())
                    .path("kind").asText());
        } catch (Exception e) {
            // DEGRADE: action.kind 解析失败按「非 capability」处理，是否应拒绝触发而非静默降级待确认
            log.warn("[DEGRADE] reminder-action-kind-parse-failed fallback to false: {}", e.toString());
            return false;
        }
    }

    /** passive declare 探 delay 队列深度（不声明参数，仅探测；连接失败按 0 放行续种） */
    private long delayQueueDepth() {
        Long count = rabbitTemplate.execute(ch ->
                (long) ch.queueDeclarePassive(MqQueue.REMINDER_DELAY).getMessageCount());
        return count == null ? 0 : count;
    }
}
