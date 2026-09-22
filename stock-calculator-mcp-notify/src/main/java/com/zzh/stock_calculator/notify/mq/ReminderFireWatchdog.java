package com.zzh.stock_calculator.notify.mq;

import com.zzh.stock_calculator.notify.entity.ReminderEntity;
import com.zzh.stock_calculator.notify.repository.ReminderRepository;
import com.zzh.stockcalc.contract.MqQueue;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 漏种补投看门狗（docs/notify/design.md §五-6，MqHeartbeatWatchdog 同款独立线程池模式）：
 * 低频校对 active at_time 提醒数与 delay 队列深度，深度不足判漏种，全量重投影补投
 * （幂等占位挡重复触发，多投无害）。bootstrap 是启动期一次性兜底，本类覆盖运行期
 * 种子意外丢失（如 broker 队列误清）。零 @Scheduled（N2）：tick 用独立单线程
 * ScheduledExecutor，不占 Spring 共享调度池。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReminderFireWatchdog {

    /** 默认校对周期：低频兜底，10 分钟一轮足够（种子丢失属异常态） */
    private static final long DEFAULT_INTERVAL_MS = 600_000L;

    private final ReminderRepository reminderRepository;
    private final ReminderSeeder reminderSeeder;
    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;

    private final ScheduledExecutorService tickPool = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "reminder-fire-watchdog");
        t.setDaemon(true);
        return t;
    });

    @PostConstruct
    public void start() {
        tickPool.scheduleWithFixedDelay(this::tick, DEFAULT_INTERVAL_MS, DEFAULT_INTERVAL_MS,
                TimeUnit.MILLISECONDS);
        log.info("[notify] 漏种看门狗已启动：interval={}ms", DEFAULT_INTERVAL_MS);
    }

    @PreDestroy
    public void stop() {
        tickPool.shutdownNow();
    }

    private void tick() {
        try {
            List<ReminderEntity> actives =
                    reminderRepository.findByStatusAndTriggerType("active", "at_time");
            if (actives.isEmpty()) {
                return;
            }
            Long depth = rabbitTemplate.execute(ch ->
                    (long) ch.queueDeclarePassive(MqQueue.REMINDER_DELAY).getMessageCount());
            long queueDepth = depth == null ? 0 : depth;
            if (queueDepth >= actives.size()) {
                return;
            }
            int reseeded = 0;
            for (ReminderEntity reminder : actives) {
                OffsetDateTime nextFireAt = resolveNextFireAt(reminder);
                if (nextFireAt != null) {
                    reminderSeeder.seed(reminder.getId(), nextFireAt);
                    reseeded++;
                }
            }
            log.warn("[notify] 看门狗判漏种：队列深度 {} < active 提醒数 {}，补投 {} 颗种子",
                    queueDepth, actives.size(), reseeded);
        } catch (Exception e) {
            // 单轮失败仅告警，下轮重试（连接抖动不应积累成退出——与心跳 watchdog 的假死退出语义不同）
            log.warn("[notify] 漏种看门狗本轮校对失败: {}", e.toString());
        }
    }

    private OffsetDateTime resolveNextFireAt(ReminderEntity reminder) {
        try {
            JsonNode spec = objectMapper.readTree(reminder.getTriggerSpec());
            return Instant.parse(spec.path("nextFireAt").asText()).atOffset(ZoneOffset.UTC);
        } catch (Exception e) {
            return null;
        }
    }
}
