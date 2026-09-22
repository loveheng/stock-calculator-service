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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
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
            OffsetDateTime nextFireAt = resolveNextFireAt(reminder);
            if (nextFireAt == null) {
                continue;
            }
            reminderSeeder.seed(reminder.getId(), nextFireAt);
            reseeded++;
        }
        log.info("[notify] bootstrap：队列深度 {} < active 提醒数 {}，全量重投影 {} 颗种子",
                queueDepth, actives.size(), reseeded);
    }

    /** 从 spec 解析 nextFireAt；已过期时刻跳过（fire 消费幂等占位的 fired_at<due 条件会挡） */
    private OffsetDateTime resolveNextFireAt(ReminderEntity reminder) {
        try {
            JsonNode spec = objectMapper.readTree(reminder.getTriggerSpec());
            return Instant.parse(spec.path("nextFireAt").asText()).atOffset(ZoneOffset.UTC);
        } catch (DateTimeParseException | NullPointerException e) {
            log.warn("[notify] bootstrap：reminder id={} nextFireAt 非法/缺失，跳过重投影（提醒不触发，可在 list 观测）",
                    reminder.getId());
            return null;
        }
    }
}
