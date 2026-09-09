package com.zzh.stock_calculator.crawler.task;

import com.zzh.stock_calculator.crawler.EmbeddingBackfillCompletedEvent;
import com.zzh.stock_calculator.crawler.embedding.config.EmbeddingEnabledCondition;
import com.zzh.stock_calculator.crawler.embedding.config.EmbeddingProperties;
import com.zzh.stock_calculator.crawler.embedding.entity.EmbeddingStatus;
import com.zzh.stock_calculator.crawler.embedding.repository.ClsArticleEmbeddingRepository;
import com.zzh.stock_calculator.crawler.embedding.service.ArticleEmbeddingService;
import com.zzh.stock_calculator.crawler.embedding.service.EmbeddingErrorClassifier;
import com.zzh.stock_calculator.crawler.embedding.service.EmbeddingQuotaGuard;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 存量回填 + 对账自愈 Task（设计文档 §4.4/§4.6/§6.1，P1 修订：范围快照 + 终态 + 完成通知）。
 *
 * <p>触发源：ApplicationReadyEvent 延迟 15s（沿用启动补录先例）+ 每小时 cron（UTC）。
 * 游标即状态：每轮开始先快照 boundary（当前时刻，秒级），仅取 boundary 内 PENDING/未处理
 * 文章逐篇嵌入，按 ctime 降序（最新优先，跑批期间检索始终覆盖最近时段）；范围内清空即
 * CAUGHT_UP 退出。运行期间新文章由增量监听实时处理，监听失败遗漏的下轮新 boundary 收编。
 *
 * <p>三类熔断：429 → QuotaGuard 置位至次日 00:00 UTC 当次退出；瞬时类（5xx/IO/超时）
 * → 批级退避重试 3 次（300ms/1s/3s）仍败当次退出下小时续；致命类（401/403，token 失效
 * /权限缺失属整体配置错误）→ markFatal 停机人工介入；其余 PERMANENT（如单篇 400 脏数据）
 * 由 service 侧 fail_count 计次，达 max-fail-attempts 落 FAILED 终态退出游标。
 *
 * <p>完成通知：本轮启动时未完成、结束后全量 DONE/FAILED → 发布
 * EmbeddingBackfillCompletedEvent（进程生命周期内一次，重启前已完成则预置标志不重发），
 * auth 侧监听发送完成邮件；无监听方/邮件未配置时静默。
 */
@Slf4j
@Component
@Conditional(EmbeddingEnabledCondition.class)
@RequiredArgsConstructor
public class EmbeddingBackfillTask {

    /** 瞬时类批级退避间隔（设计文档 §6.1：300ms → 1s → 3s） */
    static final long[] TRANSIENT_BACKOFF_MS = {300, 1000, 3000};

    private final ClsArticleEmbeddingRepository embeddingRepository;
    private final ArticleEmbeddingService embeddingService;
    private final EmbeddingQuotaGuard quotaGuard;
    private final EmbeddingProperties properties;
    private final ApplicationEventPublisher eventPublisher;

    /** 启动触发与 cron 触发可能重叠，单飞守卫 */
    private final AtomicBoolean running = new AtomicBoolean(false);

    /** 完成通知一次性标志：全量 DONE/FAILED 的完成迁移点只发一次（重启前已完成则预置不重发） */
    private final AtomicBoolean completionNotified = new AtomicBoolean(false);

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
        if (!running.compareAndSet(false, true)) {
            log.info("embedding backfill skipped, previous run still in progress, trigger={}", trigger);
            return;
        }
        try {
            doRun(trigger);
        } catch (Exception e) {
            log.error("embedding backfill unexpected error, trigger={}", trigger, e);
        } finally {
            running.set(false);
        }
    }

    private void doRun(String trigger) {
        if (quotaGuard.isFatal()) {
            log.warn("embedding backfill skipped, fatal={}, trigger={}", quotaGuard.getFatalReason(), trigger);
            return;
        }
        if (quotaGuard.isRateLimited()) {
            log.info("embedding backfill skipped, quota exhausted until {}, trigger={}",
                    quotaGuard.getExhaustedUntil(), trigger);
            return;
        }

        // 范围快照：本轮只处理快照时刻（秒级）前已入库的文章，范围内清空即 CAUGHT_UP 自动退出；
        // 运行期间新文章由增量监听实时嵌入，监听失败遗漏的下轮新 boundary 收编
        long boundaryCtime = System.currentTimeMillis() / 1000;
        long startMs = System.currentTimeMillis();
        boolean completeAtStart = isBackfillComplete();
        if (completeAtStart) {
            // 重启前已全量完成 → 预置通知标志，避免每次 cron 重复发完成邮件
            completionNotified.set(true);
        }

        int batchSize = properties.getBatchSize();
        long intervalMs = properties.getBatchIntervalMs();
        log.info(">>> embedding backfill start, trigger={}, boundary={}, progress={}/{}",
                trigger, boundaryCtime, embeddingRepository.countDone(), embeddingRepository.countArticles());

        int processed = 0;
        String exitReason = "MAX_REACHED";
        while (true) {
            if (quotaGuard.isFatal()) {
                exitReason = "FATAL";
                break;
            }
            if (quotaGuard.isRateLimited()) {
                exitReason = "QUOTA_EXHAUSTED";
                break;
            }
            List<Long> ids = embeddingRepository.findPendingArticleIds(batchSize, boundaryCtime);
            if (ids.isEmpty()) {
                exitReason = "CAUGHT_UP";
                break;
            }

            boolean stop = false;
            for (Long articleId : ids) {
                if (!quotaGuard.tryAcquireBackfill(1)) {
                    exitReason = "MAX_REACHED";
                    stop = true;
                    break;
                }
                try {
                    embeddingService.processArticle(articleId);
                    processed++;
                } catch (Exception e) {
                    EmbeddingErrorClassifier.ErrorType type = EmbeddingErrorClassifier.classify(e);
                    if (type == EmbeddingErrorClassifier.ErrorType.RATE_LIMITED) {
                        quotaGuard.markRateLimited();
                        exitReason = "QUOTA_EXHAUSTED";
                        stop = true;
                        break;
                    }
                    if (type == EmbeddingErrorClassifier.ErrorType.TRANSIENT) {
                        if (!retryWithBackoff(articleId)) {
                            exitReason = "TRANSIENT_FAIL";
                            stop = true;
                            break;
                        }
                    } else {
                        // 401/403 属整体配置错误（token 失效/权限缺失）→ fatal 停机人工介入；
                        // 其余 PERMANENT（如单篇 400 脏数据）由 service 计次，达上限落 FAILED 终态
                        if (EmbeddingErrorClassifier.isFatalAuth(e)) {
                            quotaGuard.markFatal("embedding auth rejected (401/403): " + e.getMessage());
                            exitReason = "FATAL";
                            stop = true;
                            break;
                        }
                        log.warn("permanent embedding failure, articleId={}, summary={}",
                                articleId, e.getMessage());
                    }
                }
            }
            if (stop) {
                break;
            }
            try {
                Thread.sleep(intervalMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                exitReason = "INTERRUPTED";
                break;
            }
        }

        log.info(">>> embedding backfill end, trigger={}, processed={}, exitReason={}, dailyUsed={}/{}",
                trigger, processed, exitReason, quotaGuard.getTodayCount(), quotaGuard.getDailyMaxArticles());

        maybeNotifyCompletion(completeAtStart, startMs);
    }

    /** 全量（含 FAILED 终态）是否都已处理完成 */
    private boolean isBackfillComplete() {
        return embeddingRepository.countDone() + embeddingRepository.countByStatus(EmbeddingStatus.FAILED)
                >= embeddingRepository.countArticles();
    }

    /**
     * 完成通知（一次性）：本轮启动时未完成、本轮结束后全量完成 → 发布
     * EmbeddingBackfillCompletedEvent（auth 侧监听发邮件）；CAUGHT_UP 但仍有
     * 未完成件（如监听刚失败）不触发。completeAtStart 已预置标志时不重发。
     */
    private void maybeNotifyCompletion(boolean completeAtStart, long startMs) {
        if (completeAtStart || !isBackfillComplete()) {
            return;
        }
        if (!completionNotified.compareAndSet(false, true)) {
            return;
        }
        long total = embeddingRepository.countArticles();
        long done = embeddingRepository.countDone();
        long failed = embeddingRepository.countByStatus(EmbeddingStatus.FAILED);
        long elapsedMs = System.currentTimeMillis() - startMs;
        eventPublisher.publishEvent(EmbeddingBackfillCompletedEvent.builder()
                .totalArticles(total)
                .doneCount(done)
                .failedCount(failed)
                .elapsedMs(elapsedMs)
                .build());
        log.info(">>> embedding backfill completion notified, total={}, done={}, failed={}, elapsedMs={}",
                total, done, failed, elapsedMs);
    }

    /** 瞬时失败退避重试（重试同样幂等：确定性 UUID 覆盖写）；全部失败返回 false 当次退出 */
    private boolean retryWithBackoff(Long articleId) {
        for (long backoffMs : TRANSIENT_BACKOFF_MS) {
            try {
                Thread.sleep(backoffMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            try {
                embeddingService.processArticle(articleId);
                return true;
            } catch (Exception retryError) {
                log.warn("transient embedding retry failed, articleId={}, backoffMs={}",
                        articleId, backoffMs);
            }
        }
        return false;
    }
}
