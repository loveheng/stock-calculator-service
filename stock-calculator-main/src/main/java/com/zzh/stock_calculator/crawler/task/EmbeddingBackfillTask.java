package com.zzh.stock_calculator.crawler.task;

import com.zzh.stock_calculator.crawler.EmbeddingQuotaGuard;
import com.zzh.stock_calculator.crawler.embedding.config.EmbeddingGate;
import com.zzh.stock_calculator.crawler.embedding.config.EmbeddingProperties;
import com.zzh.stock_calculator.crawler.embedding.repository.ClsArticleEmbeddingRepository;
import com.zzh.stock_calculator.crawler.embedding.service.EmbeddingTaskDispatcher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 存量对账自愈 Task（设计文档 §4.6，MQ 单路径终态）：扫缺 → 补发 task.embedding.compute，
 * 向量计算由数据服务 worker 承担，DONE 由 result.embedding.done 消费端异步落账；
 * 本 Task 不写任何 embedding 状态。
 *
 * <p>触发源：ApplicationReadyEvent 延迟 15s + 每小时 cron（UTC）；
 * embedding.backfill.enabled=false 时整体停用（E2E 共享 broker 场景必关，防扫真实库发任务）。
 * 门控仅查 isFeatureEnabled()（CF 凭据齐备性属计算端 worker 职责）。</p>
 *
 * <p>快照式扫缺：启动时一次取全量 pending ids 内存迭代。MQ 下发不改行状态，
 * 若沿用进程内路径的「批内重查」，消费端落库前会永远查回同一批 → 死循环，
 * 故不做分批重查、也不做 NOT IN 排除（数万元素 SQL 不可行）。循环内仅读
 * 额度状态决定中断（fatal → FATAL / 429 → QUOTA_EXHAUSTED / 日上限 →
 * MAX_REACHED），记账唯一入口仍是 dispatcher 内 tryAcquireBackfill(1)；
 * 单篇发布异常 warn 后继续（该篇 PENDING 留待下轮，MQ send 无 transient 退避语义）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EmbeddingBackfillTask {

    /**
     * MQ 对账快照上限：一次取全量 pending（当前库 6 万级，10 万余量充足）。
     * 不分批重查的原因见类 javadoc。
     */
    static final int RECONCILE_SNAPSHOT_LIMIT = 100_000;

    private final ClsArticleEmbeddingRepository embeddingRepository;
    private final EmbeddingQuotaGuard quotaGuard;
    private final EmbeddingProperties properties;
    private final EmbeddingGate gate;
    private final EmbeddingTaskDispatcher dispatcher;

    /** 启动触发与 cron 触发可能重叠，单飞守卫 */
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

    @Scheduled(cron = "${embedding.backfill.cron:0 5 * * * *}", zone = "UTC")
    public void cronRun() {
        runSafely("cron");
    }

    private void runSafely(String trigger) {
        if (!properties.getBackfill().isEnabled()) {
            // 总开关停用：startup/cron 两条触发路径都跳过（E2E 共享 broker 防真实库任务污染）
            log.debug("embedding backfill disabled, run skipped, trigger={}", trigger);
            return;
        }
        if (!gate.isFeatureEnabled()) {
            log.debug("embedding disabled, reconcile skipped, trigger={}", trigger);
            return;
        }
        if (!running.compareAndSet(false, true)) {
            log.info("embedding reconcile skipped, previous run still in progress, trigger={}", trigger);
            return;
        }
        try {
            doReconcileRun(trigger);
        } catch (Exception e) {
            log.error("embedding reconcile unexpected error, trigger={}", trigger, e);
        } finally {
            running.set(false);
        }
    }

    private void doReconcileRun(String trigger) {
        if (quotaGuard.isFatal()) {
            log.warn("embedding reconcile skipped, fatal={}, trigger={}", quotaGuard.getFatalReason(), trigger);
            return;
        }
        if (quotaGuard.isRateLimited()) {
            log.info("embedding reconcile skipped, quota exhausted until {}, trigger={}",
                    quotaGuard.getExhaustedUntil(), trigger);
            return;
        }

        List<Long> pendingIds = embeddingRepository.findPendingArticleIds(RECONCILE_SNAPSHOT_LIMIT,
                System.currentTimeMillis() / 1000);
        log.info(">>> embedding reconcile start, trigger={}, pending={}, dailyUsed={}/{}",
                trigger, pendingIds.size(), quotaGuard.getTodayCount(), quotaGuard.getDailyMaxArticles());

        int dispatched = 0;
        String exitReason = "CAUGHT_UP";
        for (Long articleId : pendingIds) {
            if (quotaGuard.isFatal()) {
                exitReason = "FATAL";
                break;
            }
            if (quotaGuard.isRateLimited()) {
                exitReason = "QUOTA_EXHAUSTED";
                break;
            }
            if (quotaGuard.getTodayCount() >= quotaGuard.getDailyMaxArticles()) {
                exitReason = "MAX_REACHED";
                break;
            }
            try {
                if (dispatcher.dispatchForArticle(articleId)) {
                    dispatched++;
                }
            } catch (Exception e) {
                log.warn("embedding reconcile dispatch failed, articleId={}, summary={}",
                        articleId, e.getMessage());
            }
        }

        log.info(">>> embedding reconcile end, trigger={}, dispatched={}, exitReason={}, dailyUsed={}/{}",
                trigger, dispatched, exitReason, quotaGuard.getTodayCount(), quotaGuard.getDailyMaxArticles());
        // 无完成通知：DONE 异步落库，本轮结束≠全量完成
    }
}
