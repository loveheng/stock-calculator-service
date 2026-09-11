package com.zzh.stock_calculator.crawler.embedding.service;

import com.zzh.stock_calculator.crawler.EmbeddingQuotaGuard;
import com.zzh.stock_calculator.crawler.embedding.entity.ClsArticleEmbedding;
import com.zzh.stock_calculator.crawler.embedding.entity.EmbeddingStatus;
import com.zzh.stock_calculator.crawler.embedding.repository.ClsArticleEmbeddingRepository;
import com.zzh.stock_calculator.crawler.entity.ClsArticle;
import com.zzh.stock_calculator.crawler.mq.TaskPublisher;
import com.zzh.stock_calculator.crawler.repository.ClsArticleRepository;
import com.zzh.stockcalc.contract.MessageType;
import com.zzh.stockcalc.contract.message.EmbeddingComputeTask;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * EmbeddingTaskDispatcher 单测（Mockito，无 Spring 上下文）：
 * 发布前四类拒绝分支（无文章/空文本/指纹未变/额度熔断）+ 正常下发 payload 正确性。
 * 指纹与文本口径直接复用 ArticleEmbeddingService 的包私有静态方法，测试断言同源。
 */
@ExtendWith(MockitoExtension.class)
class EmbeddingTaskDispatcherTest {

    private static final long ARTICLE_ID = 101L;

    @Mock
    private ClsArticleRepository articleRepository;

    @Mock
    private ClsArticleEmbeddingRepository embeddingRepository;

    @Mock
    private EmbeddingQuotaGuard quotaGuard;

    @Mock
    private TaskPublisher taskPublisher;

    private EmbeddingTaskDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        dispatcher = new EmbeddingTaskDispatcher(
                articleRepository, embeddingRepository, quotaGuard, taskPublisher);
    }

    private ClsArticle article(String content, String brief) {
        return ClsArticle.builder().id(ARTICLE_ID).title("【标题】测试电报")
                .content(content).brief(brief).ctime(1757480000L).level("C").build();
    }

    @Test
    @DisplayName("正常下发: payload kind/refId/text 正确且占额度计数")
    void dispatchesEligibleArticle() {
        when(articleRepository.findById(ARTICLE_ID))
                .thenReturn(Optional.of(article("正文内容。", "")));
        when(embeddingRepository.findById(ARTICLE_ID)).thenReturn(Optional.empty());
        when(quotaGuard.isAvailable()).thenReturn(true);
        when(quotaGuard.tryAcquireBackfill(1)).thenReturn(true);
        when(quotaGuard.getTodayCount()).thenReturn(1);

        boolean dispatched = dispatcher.dispatchForArticle(ARTICLE_ID);

        assertThat(dispatched).isTrue();
        ArgumentCaptor<EmbeddingComputeTask> captor = ArgumentCaptor.forClass(EmbeddingComputeTask.class);
        verify(taskPublisher).dispatchTask(eq(MessageType.TASK_EMBEDDING_COMPUTE), captor.capture());
        EmbeddingComputeTask task = captor.getValue();
        assertThat(task.getKind()).isEqualTo(EmbeddingComputeTask.KIND_CLS_ARTICLE);
        assertThat(task.getRefId()).isEqualTo(ARTICLE_ID);
        assertThat(task.getText()).isEqualTo(ArticleEmbeddingService.normalizeInput(article("正文内容。", "")));
    }

    @Test
    @DisplayName("文章不存在 → 不下发")
    void missingArticleSkips() {
        when(articleRepository.findById(ARTICLE_ID)).thenReturn(Optional.empty());

        assertThat(dispatcher.dispatchForArticle(ARTICLE_ID)).isFalse();
        verify(taskPublisher, never()).dispatchTask(any(), any());
    }

    @Test
    @DisplayName("空文本（content/brief 均缺）→ 永久性跳过不下发")
    void blankTextSkips() {
        when(articleRepository.findById(ARTICLE_ID))
                .thenReturn(Optional.of(article(null, null)));

        assertThat(dispatcher.dispatchForArticle(ARTICLE_ID)).isFalse();
        verify(taskPublisher, never()).dispatchTask(any(), any());
    }

    @Test
    @DisplayName("指纹未变（DONE + hash 相同）→ 跳过下发省额度")
    void doneWithSameHashSkips() {
        String text = ArticleEmbeddingService.normalizeInput(article("正文内容。", ""));
        ClsArticleEmbedding done = ClsArticleEmbedding.builder()
                .articleId(ARTICLE_ID)
                .status(EmbeddingStatus.DONE)
                .contentHash(ArticleEmbeddingService.sha256Hex(text))
                .build();
        when(articleRepository.findById(ARTICLE_ID))
                .thenReturn(Optional.of(article("正文内容。", "")));
        when(embeddingRepository.findById(ARTICLE_ID)).thenReturn(Optional.of(done));

        assertThat(dispatcher.dispatchForArticle(ARTICLE_ID)).isFalse();
        verify(quotaGuard, never()).tryAcquireBackfill(anyInt());
        verify(taskPublisher, never()).dispatchTask(any(), any());
    }

    @Test
    @DisplayName("DONE 但 hash 已变（文章改写）→ 重新下发")
    void doneWithChangedHashRedispatches() {
        ClsArticleEmbedding done = ClsArticleEmbedding.builder()
                .articleId(ARTICLE_ID)
                .status(EmbeddingStatus.DONE)
                .contentHash("stale-hash")
                .build();
        when(articleRepository.findById(ARTICLE_ID))
                .thenReturn(Optional.of(article("改写后的正文。", "")));
        when(embeddingRepository.findById(ARTICLE_ID)).thenReturn(Optional.of(done));
        when(quotaGuard.isAvailable()).thenReturn(true);
        when(quotaGuard.tryAcquireBackfill(1)).thenReturn(true);
        when(quotaGuard.getTodayCount()).thenReturn(1);

        assertThat(dispatcher.dispatchForArticle(ARTICLE_ID)).isTrue();
        verify(taskPublisher).dispatchTask(eq(MessageType.TASK_EMBEDDING_COMPUTE), any(EmbeddingComputeTask.class));
    }

    @Test
    @DisplayName("额度熔断（429/fatal）→ 停发不占计数")
    void quotaUnavailableDefers() {
        when(articleRepository.findById(ARTICLE_ID))
                .thenReturn(Optional.of(article("正文内容。", "")));
        when(embeddingRepository.findById(ARTICLE_ID)).thenReturn(Optional.empty());
        when(quotaGuard.isAvailable()).thenReturn(false);

        assertThat(dispatcher.dispatchForArticle(ARTICLE_ID)).isFalse();
        verify(quotaGuard, never()).tryAcquireBackfill(anyInt());
        verify(taskPublisher, never()).dispatchTask(any(), any());
    }

    @Test
    @DisplayName("日额度保险丝耗尽 → 停发")
    void dailyMaxExhaustedDefers() {
        when(articleRepository.findById(ARTICLE_ID))
                .thenReturn(Optional.of(article("正文内容。", "")));
        when(embeddingRepository.findById(ARTICLE_ID)).thenReturn(Optional.empty());
        when(quotaGuard.isAvailable()).thenReturn(true);
        when(quotaGuard.tryAcquireBackfill(1)).thenReturn(false);

        assertThat(dispatcher.dispatchForArticle(ARTICLE_ID)).isFalse();
        verify(taskPublisher, never()).dispatchTask(any(), any());
    }
}
