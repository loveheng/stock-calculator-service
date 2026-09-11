package com.zzh.stock_calculator.crawler.task;

import com.zzh.stock_calculator.crawler.EmbeddingBackfillCompletedEvent;
import com.zzh.stock_calculator.crawler.embedding.config.EmbeddingGate;
import com.zzh.stock_calculator.crawler.embedding.config.EmbeddingProperties;
import com.zzh.stock_calculator.crawler.embedding.entity.EmbeddingStatus;
import com.zzh.stock_calculator.crawler.embedding.repository.ClsArticleEmbeddingRepository;
import com.zzh.stock_calculator.crawler.embedding.service.ArticleEmbeddingService;
import com.zzh.stock_calculator.crawler.embedding.service.EmbeddingErrorClassifier;
import com.zzh.stock_calculator.crawler.embedding.service.EmbeddingTaskDispatcher;
import com.zzh.stock_calculator.crawler.EmbeddingQuotaGuard;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 存量回填 + 对账自愈 Task（设计文档 §4.4/§4.6/§6.1，P1 修订：范围快照 + 终态 + 完成通知）。
 *
 * <p>触发源：ApplicationReadyEvent 延迟 15s（沿用启动补录先例）+ 每小时 cron（UTC）；
 * embedding.backfill.enabled=false 时整体停用（E2E 共享 broker 场景必关，防扫真实库发任务）。</p>
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
 *
 * <p>R1：本 Bean 一律注册（@Scheduled 经 ScheduledBeanLazyInitializationExcludeFilter
 * 强制急切实例化），嵌入依赖经 ObjectProvider 惰性解析——未过 EmbeddingGate 门控前
 * 绝不触发 ArticleEmbeddingService/VectorStore/EmbeddingModel 实例化链。
 * 阶段 3 MQ 模式（EmbeddingTaskDispatcher 存在）下本 Task 转为发布端对账器
 * （doReconcileRun）：一次快照全量 pending ids 逐篇扫缺补发 task.embedding.compute
 * （指纹判重/额度记账均在 dispatcher 内），DONE 由 result.embedding.done 消费端
 * 异步落库——本轮结束≠全量完成，完成邮件事件不适用 MQ 模式；门控仅查
 * isFeatureEnabled()（CF 凭据齐备性属计算端 worker 职责）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EmbeddingBackfillTask {

    /** 瞬时类批级退避间隔（设计文档 §6.1：300ms → 1s → 3s） */
    static final long[] TRANSIENT_BACKOFF_MS = {300, 1000, 3000};

    /**
     * MQ 对账快照上限：一次取全量 pending（当前库 6 万级，10 万余量充足）。
     * 不分批重查的原因见 doReconcileRun javadoc。
     */
    static final int RECONCILE_SNAPSHOT_LIMIT = 100_000;

    private final ClsArticleEmbeddingRepository embeddingRepository;
    private final ObjectProvider<ArticleEmbeddingService> embeddingServiceProvider;
    private final EmbeddingQuotaGuard quotaGuard;
    private final EmbeddingProperties properties;
    private final ApplicationEventPublisher eventPublisher;
    private final EmbeddingGate gate;
    private final ObjectProvider<EmbeddingTaskDispatcher> dispatcherProvider;

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
        if (!properties.getBackfill().isEnabled()) {
            // 总开关停用：startup/cron 两条触发路径都跳过（E2E 共享 broker 防真实库任务污染）
            log.debug("embedding backfill disabled, run skipped, trigger={}", trigger);
            return;
        }
        EmbeddingTaskDispatcher dispatcher = dispatcherProvider.getIfAvailable();
        if (dispatcher != null) {
            // 阶段 3：MQ 模式 → 发布端对账器（扫缺补发 task.embedding.compute），
            // 双路径并行会双份烧 CF 额度；门控只看功能开关（凭据齐备性属 worker 职责）
            if (!gate.isFeatureEnabled()) {
                log.debug("embedding disabled, reconcile skipped, trigger={}", trigger);
                return;
            }
            runGuarded(() -> doReconcileRun(dispatcher, trigger), trigger);
            return;
        }
        if (!gate.isAvailable()) {
            // 未启用/凭据缺失：整体静默跳过（凭据缺失但开关开时 EmbeddingGate 已 WARN 一次）
            log.debug("embedding unavailable, backfill skipped, trigger={}", trigger);
            return;
        }
        runGuarded(() -> doRun(trigger), trigger);
    }

    /** 单飞守卫 + 异常兜底：启动触发与 cron 触发可能重叠，两条路径共享 */
    private void runGuarded(Runnable body, String trigger) {
        if (!running.compareAndSet(false, true)) {
            log.info("embedding run skipped, previous run still in progress, trigger={}", trigger);
            return;
        }
        try {
            body.run();
        } catch (Exception e) {
            log.error("embedding run unexpected error, trigger={}", trigger, e);
        } finally {
            running.set(false);
        }
    }

    /**
     * MQ 模式对账循环（发布端对账器）：只做「扫缺 → 批量下发」，不写任何 embedding
     * 状态（PENDING 保持到 result.embedding.done 消费端落 DONE）。
     *
     * <p>快照式扫缺：启动时一次取全量 pending ids 内存迭代。MQ 下发不改行状态，
     * 若沿用进程内路径的「批内重查」，消费端落库前会永远查回同一批 → 死循环，
     * 故不做分批重查、也不做 NOT IN 排除（数万元素 SQL 不可行）。循环内仅读
     * 额度状态决定中断（fatal → FATAL / 429 → QUOTA_EXHAUSTED / 日上限 →
     * MAX_REACHED），记账唯一入口仍是 dispatcher 内 tryAcquireBackfill(1)；
     * 单篇发布异常 warn 后继续（该篇 PENDING 留待下轮，MQ send 无 transient 退避语义）。</p>
     */
    private void doReconcileRun(EmbeddingTaskDispatcher dispatcher, String trigger) {
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
        // 无完成通知：DONE 异步落库，本轮结束≠全量完成，完成邮件语义（进程内路径）不适用
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
        ArticleEmbeddingService embeddingService = embeddingServiceProvider.getObject();
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
                embeddingServiceProvider.getObject().processArticle(articleId);
                return true;
            } catch (Exception retryError) {
                log.warn("transient embedding retry failed, articleId={}, backoffMs={}",
                        articleId, backoffMs);
            }
        }
        return false;
    }
}
