package com.zzh.stock_calculator.notify.mq;

import com.zzh.stock_calculator.notify.entity.CapabilityRequestEntity;
import com.zzh.stock_calculator.notify.entity.ReminderEntity;
import com.zzh.stock_calculator.notify.repository.CapabilityRequestRepository;
import com.zzh.stock_calculator.notify.repository.ReminderRepository;
import com.zzh.stockcalc.contract.message.NotifyPushPayload;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * capability 超时看门狗（docs/notify/design.md §六：deadline 兜底 + 降级通知，pending 表 TTL 清理）：
 * 低频扫描过期 pending → 降级通知「数据暂不可用」→ 清理行（零 @Scheduled，独立线程池，
 * ReminderFireWatchdog 同款模式）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CapabilityWatchdog {

    /** 扫描周期：deadline 默认 60s，30s 一轮保证降级及时 */
    private static final long SCAN_INTERVAL_MS = 30_000L;

    private final CapabilityRequestRepository capabilityRequestRepository;
    private final ReminderRepository reminderRepository;
    private final NotifyPublisher notifyPublisher;
    private final RabbitTemplate rabbitTemplate;

    private final ScheduledExecutorService tickPool = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "capability-watchdog");
        t.setDaemon(true);
        return t;
    });

    @PostConstruct
    public void start() {
        tickPool.scheduleWithFixedDelay(this::tick, SCAN_INTERVAL_MS, SCAN_INTERVAL_MS,
                TimeUnit.MILLISECONDS);
        log.info("[notify] capability 超时看门狗已启动：interval={}ms", SCAN_INTERVAL_MS);
    }

    @PreDestroy
    public void stop() {
        tickPool.shutdownNow();
    }

    @Transactional
    public void tick() {
        try {
            OffsetDateTime now = OffsetDateTime.now();
            for (CapabilityRequestEntity expired : capabilityRequestRepository.findByDeadlineBefore(now)) {
                degrade(expired);
                capabilityRequestRepository.delete(expired);
            }
        } catch (Exception e) {
            log.warn("[notify] capability 看门狗本轮扫描失败: {}", e.toString());
        }
    }

    /** 降级通知（§六：deadline 到未回流 →「数据暂不可用」，reminder 非活跃则只清理） */
    private void degrade(CapabilityRequestEntity expired) {
        reminderRepository.findById(expired.getReminderId())
                .filter(r -> "active".equals(r.getStatus()))
                .ifPresent(reminder -> {
                    notifyPublisher.publishPush(NotifyPushPayload.builder()
                            .userId(reminder.getUserId())
                            .reminderId(reminder.getId())
                            .title("快报")
                            .body("数据暂不可用（能力请求超时），稍后可重试")
                            .build(), expired.getTraceId());
                    log.info("[notify] capability 请求超时降级 reminderId={} traceId={}",
                            reminder.getId(), expired.getTraceId());
                });
    }
}
