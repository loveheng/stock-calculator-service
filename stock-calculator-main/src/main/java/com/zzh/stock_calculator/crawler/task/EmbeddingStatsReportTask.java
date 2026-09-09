package com.zzh.stock_calculator.crawler.task;

import com.zzh.stock_calculator.crawler.EmbeddingStatsReportEvent;
import com.zzh.stock_calculator.crawler.embedding.config.EmbeddingEnabledCondition;
import com.zzh.stock_calculator.crawler.embedding.config.EmbeddingProperties;
import com.zzh.stock_calculator.crawler.embedding.entity.EmbeddingStatus;
import com.zzh.stock_calculator.crawler.embedding.repository.ClsArticleEmbeddingRepository;
import com.zzh.stock_calculator.crawler.repository.ClsArticleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Conditional;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 向量化统计报告 Task（每 N 天一份：新增数据量 + 存量处理量；存量完成后仅报增量）。
 *
 * <p>触发：每日 cron 检查点（UTC，默认 01:00 = 北京 09:00）比对上次发送 epoch day，
 * 满 interval-days 才发——间隔严格按上次实际发送计算，不受月份长度影响；lastSent 为
 * 内存态且初始为启动当天，重启顺延、绝不轰炸。统计窗口 = 上次发送时刻 ~ 当前。
 *
 * <p>统计口径：新增电报按 ctime（cls_article 无独立入库时间戳，created_at 为
 * to_timestamp(ctime) 生成列；爬虫近实时入库，ctime ≈ 入库时间）；处理量按
 * embedded_at（回填与增量共用）；存量完成判定与回填 Task 同式（DONE + FAILED >= 总数）。
 *
 * <p>Modulith：统计在 crawler 域完成后发布 EmbeddingStatsReportEvent（基包 API），
 * auth 侧监听渲染并发送邮件；无监听方/邮箱未配置静默，不影响统计任务。
 */
@Slf4j
@Component
@Conditional(EmbeddingEnabledCondition.class)
@RequiredArgsConstructor
public class EmbeddingStatsReportTask {

    private final ClsArticleEmbeddingRepository embeddingRepository;
    private final ClsArticleRepository articleRepository;
    private final EmbeddingProperties properties;
    private final ApplicationEventPublisher eventPublisher;

    /** 上次发送所在 UTC epoch day；package-private 供同包测试推进。初始 = 启动当天 → 首封在满间隔后 */
    final AtomicLong lastSentEpochDay = new AtomicLong(Instant.now().getEpochSecond() / 86400L);

    @Scheduled(cron = "${embedding.report.cron:0 0 1 * * *}", zone = "UTC")
    public void cronCheck() {
        if (!properties.getReport().isEnabled()) {
            return;
        }
        long nowSecond = Instant.now().getEpochSecond();
        long today = nowSecond / 86400L;
        long last = lastSentEpochDay.get();
        if (today - last < properties.getReport().getIntervalDays()) {
            return;
        }
        // CAS 推进防重复发送（cron 默认单线程，防御性）
        if (!lastSentEpochDay.compareAndSet(last, today)) {
            return;
        }
        publishReport(last * 86400L, nowSecond);
    }

    private void publishReport(long windowStartSecond, long windowEndSecond) {
        long totalArticles = embeddingRepository.countArticles();
        long doneCount = embeddingRepository.countDone();
        long failedCount = embeddingRepository.countByStatus(EmbeddingStatus.FAILED);
        boolean backfillComplete = doneCount + failedCount >= totalArticles;
        OffsetDateTime since = Instant.ofEpochSecond(windowStartSecond).atOffset(ZoneOffset.UTC);
        long embeddedInWindow = embeddingRepository.countByEmbeddedAtGreaterThanEqual(since);
        long newArticleCount = articleRepository.countByCtimeGreaterThanEqual(windowStartSecond);
        long newPendingCount = embeddingRepository.countNewPendingArticles(windowStartSecond);

        eventPublisher.publishEvent(EmbeddingStatsReportEvent.builder()
                .windowStartEpochSecond(windowStartSecond)
                .windowEndEpochSecond(windowEndSecond)
                .newArticleCount(newArticleCount)
                .newPendingCount(newPendingCount)
                .totalArticles(totalArticles)
                .doneCount(doneCount)
                .failedCount(failedCount)
                .embeddedInWindow(embeddedInWindow)
                .backfillComplete(backfillComplete)
                .build());
        log.info(">>> embedding stats report published, window=[{},{}], total={}, done={}, failed={}, "
                        + "embeddedInWindow={}, newArticles={}, newPending={}, backfillComplete={}",
                windowStartSecond, windowEndSecond, totalArticles, doneCount, failedCount,
                embeddedInWindow, newArticleCount, newPendingCount, backfillComplete);
    }
}
