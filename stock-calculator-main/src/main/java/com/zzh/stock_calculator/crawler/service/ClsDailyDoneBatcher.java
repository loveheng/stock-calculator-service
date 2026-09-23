package com.zzh.stock_calculator.crawler.service;

import com.zzh.stock_calculator.common.DomainEventPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Map;

/**
 * 日报领域事件批次化（cls.daily.done 粒度债收口）：文章逐条入库（MQ 消费幂等），
 * 事件若逐条发布，mq_wait 挂起实例在批次第一篇即被唤醒——「今天的日报好了」语义偏差。
 * 本组件做静默窗聚合：同一批次文章连续入库期间只累计计数，批次静默
 * （quiet-seconds 内无新文章）或超最大延迟（max-delay-seconds）后发布一条批次完成事件。
 * <p>事件 routing key 不变（event.cls.daily.done），载荷升级为批次口径：
 * {article_count, article_id=末篇, ctime=末篇}——mq_wait 声明无 filter 时行为兼容。</p>
 * <p>record 在事务内调用，经 afterCommit 计数（回滚不计入，防幻影计数）；
 * flush 由 @Scheduled 驱动，publishAfterCommit 无事务上下文走直发。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ClsDailyDoneBatcher {

    private final DomainEventPublisher domainEventPublisher;

    /** 批次静默窗（秒）：静默满该时长视为本轮日报入库完成 */
    @Value("${crawler.cls-daily.batch-quiet-seconds:30}")
    private long quietSeconds;

    /** 批次最大延迟（秒）：长尾不断流时防事件无限延后 */
    @Value("${crawler.cls-daily.batch-max-delay-seconds:300}")
    private long maxDelaySeconds;

    private final Object lock = new Object();
    private long pendingCount;
    private long lastArticleId;
    private long lastCtime;
    private long firstAt;
    private long lastAt;

    /** 事务提交后计数（事务回滚不发，与 DomainEventPublisher.publishAfterCommit 同语义） */
    public void record(long articleId, long ctime) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    doRecord(articleId, ctime);
                }
            });
        } else {
            doRecord(articleId, ctime);
        }
    }

    private void doRecord(long articleId, long ctime) {
        synchronized (lock) {
            long now = System.currentTimeMillis();
            if (pendingCount == 0) {
                firstAt = now;
            }
            pendingCount++;
            lastArticleId = articleId;
            lastCtime = ctime;
            lastAt = now;
        }
    }

    /** 批次完成事件 flush：静默满窗或超最大延迟时发布一条批次事件（无积压零开销） */
    @Scheduled(fixedDelayString = "PT5S")
    public void flush() {
        synchronized (lock) {
            if (pendingCount == 0) {
                return;
            }
            long now = System.currentTimeMillis();
            boolean quiet = now - lastAt >= quietSeconds * 1000L;
            boolean overdue = now - firstAt >= maxDelaySeconds * 1000L;
            if (!quiet && !overdue) {
                return;
            }
            long count = pendingCount;
            long articleId = lastArticleId;
            long ctime = lastCtime;
            pendingCount = 0;
            domainEventPublisher.publishAfterCommit(
                    com.zzh.stockcalc.contract.MqKey.EVENT_CLS_DAILY_DONE,
                    Map.of("article_count", count, "article_id", articleId, "ctime", ctime));
            log.info("cls daily batch event published: articleCount={} lastArticleId={}", count, articleId);
        }
    }
}
