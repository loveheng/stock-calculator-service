package com.zzh.stock_calculator.kg.task;

import com.zzh.stock_calculator.kg.config.KgProperties;
import com.zzh.stock_calculator.kg.mq.KgExtractPublisher;
import com.zzh.stock_calculator.monitor.AppTaskHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * KG 历史回填任务（二期，cls-news-kg.md §7/§11，MQ 单路径终态）：汇编存量按 ctime 最旧优先
 * 分批补发 task.kg.extract，抽取/摄取/融合全走既有管线（worker 与结果通道零改动）；
 * 发布核复用 KgExtractPublisher.publishBackfillBatch()（ASC 扫描窗口即游标，追平后空转）。
 *
 * <p>触发源：ApplicationReadyEvent 延迟触发 + pull_task_config CALENDAR 行每 30 分钟认领
 * （job.kg.backfill，Asia/Shanghai）；kg.backfill.enabled=false 整体停用（E2E 共享 broker
 * 场景必关，防扫真实库发任务；startup 与 DB 调度两条路径同门控）。单飞守卫防两触发源重叠。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KgBackfillTask implements AppTaskHandler {

    private final KgExtractPublisher publisher;
    private final KgProperties properties;

    /** 启动触发与定时触发可能重叠，单飞守卫 */
    private final AtomicBoolean running = new AtomicBoolean(false);

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        long delayMs = properties.getBackfill().getStartupDelay().toMillis();
        Thread.startVirtualThread(() -> {
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            runSafely("startup");
        });
    }

    @Override
    public String taskCode() {
        return AppTaskHandler.TASK_KG_BACKFILL;
    }

    @Override
    public void run() {
        runSafely("app-task");
    }

    private void runSafely(String trigger) {
        if (!properties.getBackfill().isEnabled()) {
            // 总开关停用：startup/定时两条触发路径都跳过（E2E 共享 broker 防真实库任务污染）
            log.debug("kg backfill disabled, run skipped, trigger={}", trigger);
            return;
        }
        if (!running.compareAndSet(false, true)) {
            log.info("kg backfill skipped, previous run still in progress, trigger={}", trigger);
            return;
        }
        try {
            int dispatched = publisher.publishBackfillBatch();
            log.info("kg backfill round done, trigger={}, dispatched={}", trigger, dispatched);
        } catch (Exception e) {
            log.error("kg backfill unexpected error, trigger={}", trigger, e);
        } finally {
            running.set(false);
        }
    }
}
