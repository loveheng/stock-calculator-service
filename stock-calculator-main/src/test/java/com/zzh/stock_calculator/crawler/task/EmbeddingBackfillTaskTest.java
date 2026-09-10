package com.zzh.stock_calculator.crawler.task;

import com.openai.core.http.Headers;
import com.openai.errors.UnexpectedStatusCodeException;
import com.zzh.stock_calculator.crawler.EmbeddingBackfillCompletedEvent;
import com.zzh.stock_calculator.crawler.embedding.config.EmbeddingGate;
import com.zzh.stock_calculator.crawler.embedding.config.EmbeddingProperties;
import com.zzh.stock_calculator.crawler.embedding.repository.ClsArticleEmbeddingRepository;
import com.zzh.stock_calculator.crawler.embedding.service.ArticleEmbeddingService;
import com.zzh.stock_calculator.crawler.EmbeddingQuotaGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * EmbeddingBackfillTask 编排逻辑单测（Mockito，无 Spring 上下文）：
 * 范围快照参数透传、完成事件一次性发布（完成迁移判定 + 重启不重发）、
 * 401 → markFatal 停机、日上限熔断不发起嵌入。
 */
@ExtendWith(MockitoExtension.class)
class EmbeddingBackfillTaskTest {

    @Mock
    private ClsArticleEmbeddingRepository embeddingRepository;

    @Mock
    private ArticleEmbeddingService embeddingService;

    @Mock
    private ObjectProvider<ArticleEmbeddingService> embeddingServiceProvider;

    @Mock
    private EmbeddingQuotaGuard quotaGuard;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private EmbeddingProperties properties;

    private EmbeddingBackfillTask task;

    @BeforeEach
    void setUp() {
        properties = new EmbeddingProperties();
        properties.setBatchSize(2);
        properties.setBatchIntervalMs(0);
        properties.setDailyMaxArticles(100);
        // 门控开启态（R1）：enabled=true + 凭据齐备，等价生产配置
        properties.setEnabled(true);
        properties.getCloudflare().setAccountId("acc-test");
        properties.getCloudflare().setApiToken("tok-test");
        task = new EmbeddingBackfillTask(embeddingRepository, embeddingServiceProvider,
                quotaGuard, properties, eventPublisher, new EmbeddingGate(properties));
        lenient().when(embeddingServiceProvider.getObject()).thenReturn(embeddingService);

        lenient().when(quotaGuard.isFatal()).thenReturn(false);
        lenient().when(quotaGuard.isRateLimited()).thenReturn(false);
        lenient().when(quotaGuard.tryAcquireBackfill(anyInt())).thenReturn(true);
        lenient().when(embeddingRepository.countByStatus(any())).thenReturn(0L);
        lenient().when(embeddingRepository.countDone()).thenReturn(0L);
        // 默认视为远未完成（完成判定 countDone + countFailed >= countArticles 不成立）
        lenient().when(embeddingRepository.countArticles()).thenReturn(Long.MAX_VALUE);
    }

    @Test
    @DisplayName("门控关闭: 未启用/凭据缺失 → 整轮跳过，不解析 Service 不触仓储")
    void gateClosedSkipsRun() {
        properties.setEnabled(false);

        task.cronRun();

        verify(embeddingServiceProvider, never()).getObject();
        verify(embeddingService, never()).processArticle(anyLong());
        verify(embeddingRepository, never()).findPendingArticleIds(anyInt(), anyLong());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("完成迁移发布事件: 本轮启动未完成 → 结束后全量 DONE → 发布一次; 重启后不重发")
    void completionEventPublishedOnce() {
        when(embeddingRepository.findPendingArticleIds(anyInt(), anyLong()))
                .thenReturn(List.of(101L), List.of());
        // 启动判定 9/10 未完成 → 嵌入 101 后 10/10 完成
        when(embeddingRepository.countDone()).thenReturn(9L, 10L);
        when(embeddingRepository.countArticles()).thenReturn(10L);

        task.cronRun();
        verify(eventPublisher, times(1)).publishEvent(any(EmbeddingBackfillCompletedEvent.class));

        // 第二轮：启动即已完成（等价重启后场景）→ 预置标志，不重发
        task.cronRun();
        verify(eventPublisher, times(1)).publishEvent(any(EmbeddingBackfillCompletedEvent.class));
    }

    @Test
    @DisplayName("CAUGHT_UP 但仍有未完成件 → 不发布")
    void caughtUpButIncompleteSkipsEvent() {
        when(embeddingRepository.findPendingArticleIds(anyInt(), anyLong())).thenReturn(List.of());
        when(embeddingRepository.countDone()).thenReturn(5L);
        when(embeddingRepository.countArticles()).thenReturn(10L);

        task.cronRun();

        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("启动即已完成（重启后首轮）→ 不重发")
    void completeAtStartSkipsEvent() {
        when(embeddingRepository.findPendingArticleIds(anyInt(), anyLong())).thenReturn(List.of());
        when(embeddingRepository.countDone()).thenReturn(10L);
        when(embeddingRepository.countArticles()).thenReturn(10L);

        task.cronRun();

        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("范围快照: 游标查询透传本轮启动时刻（秒级 boundary）")
    void boundarySnapshotPassedToCursor() {
        when(embeddingRepository.findPendingArticleIds(anyInt(), anyLong())).thenReturn(List.of());
        when(embeddingRepository.countDone()).thenReturn(0L);
        when(embeddingRepository.countArticles()).thenReturn(1L);

        long before = System.currentTimeMillis() / 1000;
        task.cronRun();
        long after = System.currentTimeMillis() / 1000;

        ArgumentCaptor<Long> boundary = ArgumentCaptor.forClass(Long.class);
        verify(embeddingRepository).findPendingArticleIds(eq(2), boundary.capture());
        assertThat(boundary.getValue()).isBetween(before, after);
    }

    @Test
    @DisplayName("401 致命错误 → markFatal 停机且不发布完成事件")
    void fatalAuthStopsRun() {
        UnexpectedStatusCodeException unauthorized = UnexpectedStatusCodeException.builder()
                .statusCode(401)
                .headers(Headers.builder().put("x-test", "1").build())
                .build();
        when(embeddingRepository.findPendingArticleIds(anyInt(), anyLong()))
                .thenReturn(List.of(101L));
        doThrow(unauthorized).when(embeddingService).processArticle(101L);

        task.cronRun();

        verify(quotaGuard).markFatal(contains("401"));
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("日上限熔断: tryAcquireBackfill 拒绝 → 不发起嵌入")
    void maxReachedSkipsProcessing() {
        when(quotaGuard.tryAcquireBackfill(anyInt())).thenReturn(false);
        when(embeddingRepository.findPendingArticleIds(anyInt(), anyLong()))
                .thenReturn(List.of(1L, 2L));

        task.cronRun();

        verify(embeddingService, never()).processArticle(anyLong());
        verify(eventPublisher, never()).publishEvent(any());
    }
}
