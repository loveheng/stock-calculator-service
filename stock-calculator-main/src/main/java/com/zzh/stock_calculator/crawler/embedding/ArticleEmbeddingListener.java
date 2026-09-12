package com.zzh.stock_calculator.crawler.embedding;

import com.zzh.stock_calculator.crawler.embedding.config.EmbeddingGate;
import com.zzh.stock_calculator.crawler.embedding.service.EmbeddingTaskDispatcher;
import com.zzh.stock_calculator.crawler.event.ArticleSavedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 增量管道监听（设计文档 §4.5，MQ 单路径终态）：文章事务提交后异步经
 * EmbeddingTaskDispatcher 下发 task.embedding.compute（CF 计算由数据服务 worker 承担，
 * 额度上移发布端）。发布失败不重试不标状态：PENDING 留给对账任务补发（D6），主链路不受影响。
 *
 * <p>门控仅查 isFeatureEnabled()：CF 凭据齐备性属计算端 worker 职责，主服务缺失凭据
 * 不应阻塞任务下发；embedding.enabled=false 时整体关闭。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ArticleEmbeddingListener {

    private final EmbeddingTaskDispatcher dispatcher;
    private final EmbeddingGate gate;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onArticleSaved(ArticleSavedEvent event) {
        if (!gate.isFeatureEnabled()) {
            log.debug("embedding disabled, incremental dispatch skipped, articleId={}",
                    event.getArticleId());
            return;
        }
        try {
            boolean dispatched = dispatcher.dispatchForArticle(event.getArticleId());
            log.debug("incremental embedding dispatch done, articleId={}, dispatched={}",
                    event.getArticleId(), dispatched);
        } catch (Exception e) {
            log.warn("embedding task dispatch failed, articleId={}, summary={}",
                    event.getArticleId(), e.getMessage());
        }
    }
}
