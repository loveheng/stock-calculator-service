package com.zzh.stock_calculator.crawler.embedding;

import com.zzh.stock_calculator.crawler.embedding.config.EmbeddingGate;
import com.zzh.stock_calculator.crawler.embedding.config.EmbeddingProperties;
import com.zzh.stock_calculator.crawler.embedding.service.EmbeddingTaskDispatcher;
import com.zzh.stock_calculator.crawler.event.ArticleSavedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ArticleEmbeddingListener 门控单测（Mockito，无 Spring 上下文）：
 * MQ 单路径终态——功能开关开启即下发任务（CF 凭据齐备性属计算端 worker 职责，
 * 主服务缺凭据不阻塞下发），关闭则跳过。
 */
@ExtendWith(MockitoExtension.class)
class ArticleEmbeddingListenerTest {

    private static final long ARTICLE_ID = 101L;

    @Mock
    private EmbeddingTaskDispatcher dispatcher;

    private EmbeddingProperties properties;

    private ArticleEmbeddingListener listener;

    @BeforeEach
    void setUp() {
        properties = new EmbeddingProperties();
        listener = new ArticleEmbeddingListener(dispatcher, new EmbeddingGate(properties));
    }

    private ArticleSavedEvent event() {
        return ArticleSavedEvent.builder().articleId(ARTICLE_ID).ctime(1757480000L).build();
    }

    @Test
    @DisplayName("功能开 → 下发任务（不查凭据）")
    void dispatchesWhenFeatureEnabled() {
        properties.setEnabled(true);

        listener.onArticleSaved(event());

        verify(dispatcher).dispatchForArticle(ARTICLE_ID);
    }

    @Test
    @DisplayName("功能关 → 不下发")
    void featureDisabledSkips() {
        properties.setEnabled(false);

        listener.onArticleSaved(event());

        verify(dispatcher, never()).dispatchForArticle(anyLong());
    }
}
