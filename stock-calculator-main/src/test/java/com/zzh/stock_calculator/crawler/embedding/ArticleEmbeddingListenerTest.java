package com.zzh.stock_calculator.crawler.embedding;

import com.zzh.stock_calculator.crawler.embedding.config.EmbeddingGate;
import com.zzh.stock_calculator.crawler.embedding.config.EmbeddingProperties;
import com.zzh.stock_calculator.crawler.embedding.service.ArticleEmbeddingService;
import com.zzh.stock_calculator.crawler.embedding.service.EmbeddingTaskDispatcher;
import com.zzh.stock_calculator.crawler.event.ArticleSavedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ArticleEmbeddingListener 双路径门控单测（Mockito，无 Spring 上下文）：
 * MQ 模式（dispatcher 存在）仅查功能开关即下发——CF 凭据齐备性属计算端 worker 职责，
 * 主服务缺凭据不阻塞下发；JVM 模式走进程内嵌入且查 isAvailable()（含凭据判定）。
 */
@ExtendWith(MockitoExtension.class)
class ArticleEmbeddingListenerTest {

    private static final long ARTICLE_ID = 101L;

    @Mock
    private ArticleEmbeddingService embeddingService;

    @Mock
    private ObjectProvider<ArticleEmbeddingService> embeddingServiceProvider;

    @Mock
    private ObjectProvider<EmbeddingTaskDispatcher> dispatcherProvider;

    @Mock
    private EmbeddingTaskDispatcher dispatcher;

    private EmbeddingProperties properties;

    private ArticleEmbeddingListener listener;

    @BeforeEach
    void setUp() {
        properties = new EmbeddingProperties();
        listener = new ArticleEmbeddingListener(embeddingServiceProvider, dispatcherProvider,
                new EmbeddingGate(properties));
        lenient().when(embeddingServiceProvider.getObject()).thenReturn(embeddingService);
    }

    private ArticleSavedEvent event() {
        return ArticleSavedEvent.builder().articleId(ARTICLE_ID).ctime(1757480000L).build();
    }

    @Test
    @DisplayName("MQ 模式 + 功能开 → 下发任务（不查凭据不触进程内嵌入）")
    void mqModeDispatchesWhenFeatureEnabled() {
        properties.setEnabled(true);
        when(dispatcherProvider.getIfAvailable()).thenReturn(dispatcher);

        listener.onArticleSaved(event());

        verify(dispatcher).dispatchForArticle(ARTICLE_ID);
        verify(embeddingServiceProvider, never()).getObject();
        verify(embeddingService, never()).processArticle(anyLong());
    }

    @Test
    @DisplayName("MQ 模式 + 功能关 → 不下发也不触进程内路径")
    void mqModeFeatureDisabledSkips() {
        properties.setEnabled(false);
        when(dispatcherProvider.getIfAvailable()).thenReturn(dispatcher);

        listener.onArticleSaved(event());

        verify(dispatcher, never()).dispatchForArticle(anyLong());
        verify(embeddingServiceProvider, never()).getObject();
    }

    @Test
    @DisplayName("JVM 模式（dispatcher 缺位）→ 门控含凭据，齐备时进程内嵌入")
    void jvmModeProcessesArticle() {
        properties.setEnabled(true);
        properties.getCloudflare().setAccountId("acc-test");
        properties.getCloudflare().setApiToken("tok-test");

        listener.onArticleSaved(event());

        verify(embeddingServiceProvider).getObject();
        verify(embeddingService).processArticle(ARTICLE_ID);
    }

    @Test
    @DisplayName("JVM 模式 + 凭据缺失 → 整体跳过，不解析嵌入服务")
    void jvmModeMissingCredentialsSkips() {
        properties.setEnabled(true);

        listener.onArticleSaved(event());

        verify(embeddingServiceProvider, never()).getObject();
        verify(embeddingService, never()).processArticle(anyLong());
    }
}
