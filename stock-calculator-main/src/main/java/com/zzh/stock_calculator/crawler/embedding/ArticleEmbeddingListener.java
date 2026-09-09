package com.zzh.stock_calculator.crawler.embedding;

import com.zzh.stock_calculator.crawler.embedding.config.EmbeddingEnabledCondition;
import com.zzh.stock_calculator.crawler.embedding.service.ArticleEmbeddingService;
import com.zzh.stock_calculator.crawler.embedding.service.EmbeddingErrorClassifier;
import com.zzh.stock_calculator.crawler.event.ArticleSavedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Conditional;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 增量管道监听（设计文档 §4.5）：文章事务提交后异步嵌入。
 * 失败 catch-all → Service 内已标 PENDING + error，由每小时回填 Task 对账自愈（≤1h）；
 * 绝不向主链路传播（爬虫入库不受任何影响）。
 */
@Slf4j
@Component
@Conditional(EmbeddingEnabledCondition.class)
@RequiredArgsConstructor
public class ArticleEmbeddingListener {

    private final ArticleEmbeddingService embeddingService;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onArticleSaved(ArticleSavedEvent event) {
        try {
            embeddingService.processArticle(event.getArticleId());
            log.debug("incremental embedding done, articleId={}", event.getArticleId());
        } catch (Exception e) {
            log.warn("incremental embedding failed, articleId={}, type={}, summary={}",
                    event.getArticleId(), EmbeddingErrorClassifier.classify(e), e.getMessage());
        }
    }
}
