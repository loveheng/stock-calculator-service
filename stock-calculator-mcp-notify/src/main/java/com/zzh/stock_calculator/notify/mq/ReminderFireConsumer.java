package com.zzh.stock_calculator.notify.mq;

import com.zzh.stock_calculator.notify.entity.ReminderEntity;
import com.zzh.stock_calculator.notify.repository.ReminderRepository;
import com.zzh.stockcalc.contract.MqQueue;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;

/**
 * fire 队列消费者（docs/notify/design.md §五）：种子到期死信落地即触发。
 * 链路：读 reminder → N7 运行期限幅（suppress_until 窗口内记 suppressed 不触发）→
 * triggerOccupy CAS 幂等占位（重复种子/已 done/cancelled 直接丢弃）→
 * 重复型自我续种（§五-3，once 置 done）→ 委托 {@link ReminderActionExecutor} 分发动作。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReminderFireConsumer {

    private final ReminderRepository reminderRepository;
    private final ReminderRepeater reminderRepeater;
    private final ReminderActionExecutor actionExecutor;

    /** 消费全程一个事务：triggerOccupy/markSuppressed 是 @Modifying 更新，缺事务抛 TransactionRequiredException */
    @org.springframework.transaction.annotation.Transactional
    @RabbitListener(queues = MqQueue.REMINDER_FIRE)
    public void onFire(String reminderIdText) {
        Long reminderId;
        try {
            reminderId = Long.parseLong(reminderIdText.trim());
        } catch (NumberFormatException e) {
            log.warn("[notify] fire payload 非法 reminder_id: {}", reminderIdText);
            return;
        }

        OffsetDateTime due = OffsetDateTime.now();
        ReminderEntity reminder = reminderRepository.findById(reminderId).orElse(null);
        if (reminder == null) {
            log.warn("[notify] fire 未找到 reminder id={}（已物理删除，跳过）", reminderId);
            return;
        }

        // N7 运行期限幅：冷静期内命中只计数不触发
        OffsetDateTime now = OffsetDateTime.now();
        if (reminder.getSuppressUntil() != null && reminder.getSuppressUntil().isAfter(now)) {
            reminderRepository.markSuppressed(reminderId);
            log.info("[notify] reminder id={} suppressed（冷静期内）", reminderId);
            return;
        }

        // 幂等占位：返回 0 = 重复种子 / 已取消（种子不回收，status 挡）
        if (reminderRepository.triggerOccupy(reminderId, due) == 0) {
            log.info("[notify] reminder id={} fire 判重未过（重复种子或非 active，跳过）", reminderId);
            return;
        }

        // 重复型自我续种（§五-3）；一次性置 done 不续
        reminderRepeater.reseedOrComplete(reminder, due);

        actionExecutor.execute(reminder);
    }
}
