package com.zzh.stock_calculator.crawler.embedding;

import com.zzh.stock_calculator.crawler.embedding.config.EmbeddingGate;
import com.zzh.stock_calculator.crawler.embedding.service.ArticleEmbeddingService;
import com.zzh.stock_calculator.crawler.embedding.service.EmbeddingErrorClassifier;
import com.zzh.stock_calculator.crawler.embedding.service.EmbeddingTaskDispatcher;
import com.zzh.stock_calculator.crawler.event.ArticleSavedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 增量管道监听（设计文档 §4.5）：文章事务提交后异步嵌入。
 * 失败 catch-all → Service 内已标 PENDING + error，由每小时回填 Task 对账自愈（≤1h）；
 * 绝不向主链路传播（爬虫入库不受任何影响）。
 *
 * <p>双路径（阶段 3，回退开关见 §8）：datasvc.mq.enabled=true → 经
 * EmbeddingTaskDispatcher 下发 task.embedding.compute（CF 计算移交数据服务 worker，
 * 额度上移发布端）；false（默认）→ 原进程内路径不变。门控分支随路径区分：
 * MQ 路径仅查 isFeatureEnabled()（CF 凭据齐备性属计算端 worker 职责，主服务
 * 不应因缺凭据阻塞下发）；进程内路径查 isAvailable()（含凭据判定），
 * embedding.enabled=false 时两条路径均整体关闭。</p>
 *
 * <p>R1：Bean 一律注册，未过 EmbeddingGate 门控前不解析 ArticleEmbeddingService /
 * EmbeddingTaskDispatcher（避免触发 VectorStore/EmbeddingModel 重 Bean 实例化链）；
 * 事务事件触发首次交付时本 Bean 才被实例化（Framework 懒加载事件监听器语义），
 * 构造仅持轻量引用。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ArticleEmbeddingListener {

    private final ObjectProvider<ArticleEmbeddingService> embeddingServiceProvider;
    private final ObjectProvider<EmbeddingTaskDispatcher> dispatcherProvider;
    private final EmbeddingGate gate;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onArticleSaved(ArticleSavedEvent event) {
        EmbeddingTaskDispatcher dispatcher = dispatcherProvider.getIfAvailable();
        if (dispatcher != null) {
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
                // 发布失败不重试不标状态：PENDING 留给对账任务补发（D6），主链路不受影响
                log.warn("embedding task dispatch failed, articleId={}, type={}, summary={}",
                        event.getArticleId(), EmbeddingErrorClassifier.classify(e), e.getMessage());
            }
            return;
        }
        if (!gate.isAvailable()) {
            log.debug("embedding unavailable, incremental embedding skipped, articleId={}",
                    event.getArticleId());
            return;
        }
        try {
            embeddingServiceProvider.getObject().processArticle(event.getArticleId());
            log.debug("incremental embedding done, articleId={}", event.getArticleId());
        } catch (Exception e) {
            log.warn("incremental embedding failed, articleId={}, type={}, summary={}",
                    event.getArticleId(), EmbeddingErrorClassifier.classify(e), e.getMessage());
        }
    }
}
