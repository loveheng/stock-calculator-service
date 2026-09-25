package com.zzh.stock_calculator.notify.mq;

import com.zzh.stock_calculator.notify.entity.ReminderEntity;
import com.zzh.stock_calculator.notify.repository.ReminderRepository;
import com.zzh.stockcalc.contract.MqQueue;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * 启动 bootstrap（docs/notify/design.md §五-5）：进程重启「钟」不丢的兜底。
 * 启动时扫全部 active 的 at_time 提醒，若 delay 队列深度 < active 数量则
 * 全量重投影种子（幂等占位挡重复触发，多投无害）；深度足够则视为种子在途，跳过。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotifyBootstrap implements ApplicationRunner {

    private final ReminderRepository reminderRepository;
    private final ReminderSeeder reminderSeeder;
    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    @Override
    public void run(ApplicationArguments args) {
        List<ReminderEntity> actives =
                reminderRepository.findByStatusAndTriggerType("active", "at_time");
        if (actives.isEmpty()) {
            log.info("[notify] bootstrap：无 active at_time 提醒，跳过");
            return;
        }

        Long depth = rabbitTemplate.execute(ch ->
                (long) ch.queueDeclarePassive(MqQueue.REMINDER_DELAY).getMessageCount());
        long queueDepth = depth == null ? 0 : depth;
        if (queueDepth >= actives.size()) {
            log.info("[notify] bootstrap：delay 队列深度 {} ≥ active 提醒数 {}，种子在途跳过重投影",
                    queueDepth, actives.size());
            return;
        }

        int reseeded = 0;
        for (ReminderEntity reminder : actives) {
            OffsetDateTime nextFireAt;
            try {
                nextFireAt = resolveNextFireAt(reminder);
            } catch (Exception e) {
                // 确定性损坏（同看门狗口径）：置 failed 显式化，既不静默跳过也不让
                // readTree 异常上抛炸掉启动（ApplicationRunner 抛异常会中止进程）
                markFailed(reminder, e);
                continue;
            }
            reminderSeeder.seed(reminder.getId(), nextFireAt);
            reseeded++;
        }
        log.info("[notify] bootstrap：队列深度 {} < active 提醒数 {}，全量重投影 {} 颗种子",
                queueDepth, actives.size(), reseeded);
    }

    /** 无 catch 直抛：解析失败与 seed 失败分流——前者确定性损坏置 failed，后者瞬时抖动下轮重启再试 */
    private OffsetDateTime resolveNextFireAt(ReminderEntity reminder) {
        JsonNode spec = objectMapper.readTree(reminder.getTriggerSpec());
        return Instant.parse(spec.path("nextFireAt").asText()).atOffset(ZoneOffset.UTC);
    }

    /** 解析损坏显式失败态：bootstrap 无事务上下文，markFailed 经 TransactionTemplate 执行 */
    private void markFailed(ReminderEntity reminder, Exception cause) {
        log.error("[notify] bootstrap：reminder id={} triggerSpec 解析失败（确定性损坏），置 failed 终止调度",
                reminder.getId(), cause);
        Integer updated = transactionTemplate.execute(txn ->
                reminderRepository.markFailed(reminder.getId()));
        if (updated != null && updated == 0) {
            log.info("[notify] bootstrap：reminder id={} 标记 failed 时已非 active（并发已正常完结），跳过",
                    reminder.getId());
        }
    }
}
