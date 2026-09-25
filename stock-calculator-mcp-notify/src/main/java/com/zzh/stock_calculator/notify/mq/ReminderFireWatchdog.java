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
import org.springframework.transaction.support.TransactionTemplate;
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
    private final TransactionTemplate transactionTemplate;

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
            checkAndReseed();
        } catch (Exception e) {
            // 单轮失败仅告警，下轮重试（连接抖动不应积累成退出——与心跳 watchdog 的假死退出语义不同）
            log.warn("[notify] 漏种看门狗本轮校对失败: {}", e.toString());
        }
    }

    /** 单轮校对：队列深度不足判漏种，全量重投影（包私有便于单测；异常上抛由 tick 统一兜） */
    void checkAndReseed() {
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
            OffsetDateTime nextFireAt;
            try {
                nextFireAt = resolveNextFireAt(reminder);
            } catch (Exception e) {
                // 确定性损坏（登记期已过结构校验，损坏只可能来自手工改库或写入/读取格式演进
                // 失配）：置 failed 终止补种，不留在 active 里每轮空转漏触发
                markFailed(reminder, e);
                continue;
            }
            reminderSeeder.seed(reminder.getId(), nextFireAt);
            reseeded++;
        }
        log.warn("[notify] 看门狗判漏种：队列深度 {} < active 提醒数 {}，补投 {} 颗种子",
                queueDepth, actives.size(), reseeded);
    }

    /** 无 catch 直抛：解析失败与 seed 失败必须分流——前者确定性损坏置 failed，后者瞬时抖动留给 tick 重试 */
    private OffsetDateTime resolveNextFireAt(ReminderEntity reminder) {
        JsonNode spec = objectMapper.readTree(reminder.getTriggerSpec());
        return Instant.parse(spec.path("nextFireAt").asText()).atOffset(ZoneOffset.UTC);
    }

    /** 解析损坏显式失败态：看门狗线程无事务上下文，markFailed 经 TransactionTemplate 执行 */
    private void markFailed(ReminderEntity reminder, Exception cause) {
        log.error("[notify] reminder id={} triggerSpec 解析失败（确定性损坏），置 failed 停止补种",
                reminder.getId(), cause);
        Integer updated = transactionTemplate.execute(txn ->
                reminderRepository.markFailed(reminder.getId()));
        if (updated != null && updated == 0) {
            log.info("[notify] reminder id={} 标记 failed 时已非 active（并发已正常完结），跳过", reminder.getId());
        }
    }
}
