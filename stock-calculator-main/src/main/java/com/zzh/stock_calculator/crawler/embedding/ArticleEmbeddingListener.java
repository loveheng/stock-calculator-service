package com.zzh.stock_calculator.crawler.embedding;

import com.zzh.stock_calculator.crawler.embedding.config.EmbeddingGate;
import com.zzh.stock_calculator.crawler.embedding.service.ArticleEmbeddingService;
import com.zzh.stock_calculator.crawler.embedding.service.EmbeddingErrorClassifier;
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
 * <p>R1：Bean 一律注册，未过 EmbeddingGate 门控前不解析 ArticleEmbeddingService
 * （避免触发 VectorStore/EmbeddingModel 重 Bean 实例化链）；事务事件触发首次交付时
 * 本 Bean 才被实例化（Framework 懒加载事件监听器语义），构造仅持轻量引用。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ArticleEmbeddingListener {

    private final ObjectProvider<ArticleEmbeddingService> embeddingServiceProvider;
    private final EmbeddingGate gate;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onArticleSaved(ArticleSavedEvent event) {
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
