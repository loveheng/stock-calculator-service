package com.zzh.stock_calculator.crawler.task;

import com.zzh.stock_calculator.crawler.EmbeddingQuotaGuard;
import com.zzh.stock_calculator.crawler.embedding.config.EmbeddingGate;
import com.zzh.stock_calculator.crawler.embedding.config.EmbeddingProperties;
import com.zzh.stock_calculator.crawler.embedding.repository.ClsArticleEmbeddingRepository;
import com.zzh.stock_calculator.crawler.embedding.service.EmbeddingTaskDispatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * EmbeddingBackfillTask 对账逻辑单测（Mockito，无 Spring 上下文）：
 * 门控/总开关跳过、范围快照参数透传（RECONCILE_SNAPSHOT_LIMIT 全量快照）、
 * 扫缺逐篇下发、日上限与额度熔断中断。
 */
@ExtendWith(MockitoExtension.class)
class EmbeddingBackfillTaskTest {

    @Mock
    private ClsArticleEmbeddingRepository embeddingRepository;

    @Mock
    private EmbeddingTaskDispatcher dispatcher;

    @Mock
    private EmbeddingQuotaGuard quotaGuard;

    private EmbeddingProperties properties;

    private EmbeddingBackfillTask task;

    @BeforeEach
    void setUp() {
        properties = new EmbeddingProperties();
        properties.setDailyMaxArticles(100);
        // 总开关开启（E2E 防污染开关在测试内显式关闭时另测）
        properties.getBackfill().setEnabled(true);
        // 门控开启态（R1）：enabled=true + 凭据齐备，等价生产配置
        properties.setEnabled(true);
        properties.getCloudflare().setAccountId("acc-test");
        properties.getCloudflare().setApiToken("tok-test");
        task = new EmbeddingBackfillTask(embeddingRepository, quotaGuard, properties,
                new EmbeddingGate(properties), dispatcher);

        lenient().when(quotaGuard.isFatal()).thenReturn(false);
        lenient().when(quotaGuard.isRateLimited()).thenReturn(false);
        lenient().when(quotaGuard.getTodayCount()).thenReturn(0);
        lenient().when(quotaGuard.getDailyMaxArticles()).thenReturn(properties.getDailyMaxArticles());
    }

    @Test
    @DisplayName("门控关闭: embedding.enabled=false → 整轮跳过，不扫库不下发")
    void gateClosedSkipsRun() {
        properties.setEnabled(false);

        task.cronRun();

        verify(embeddingRepository, never()).findPendingArticleIds(anyInt(), anyLong());
        verify(dispatcher, never()).dispatchForArticle(anyLong());
    }

    @Test
    @DisplayName("回填总开关关闭: backfill.enabled=false → startup/cron 全停，不触派发不扫库")
    void backfillDisabledSkipsRun() {
        properties.getBackfill().setEnabled(false);

        task.cronRun();

        verify(embeddingRepository, never()).findPendingArticleIds(anyInt(), anyLong());
        verify(dispatcher, never()).dispatchForArticle(anyLong());
    }

    @Test
    @DisplayName("快照扫缺: 全量 pending 一次取出（RECONCILE_SNAPSHOT_LIMIT）逐篇下发")
    void reconcileDispatchesAllPending() {
        when(embeddingRepository.findPendingArticleIds(anyInt(), anyLong()))
                .thenReturn(List.of(101L, 202L));

        task.cronRun();

        ArgumentCaptor<Long> boundary = ArgumentCaptor.forClass(Long.class);
        verify(embeddingRepository).findPendingArticleIds(
                eq(EmbeddingBackfillTask.RECONCILE_SNAPSHOT_LIMIT), boundary.capture());
        assertThat(boundary.getValue()).isBetween(
                System.currentTimeMillis() / 1000 - 5, System.currentTimeMillis() / 1000);
        verify(dispatcher).dispatchForArticle(101L);
        verify(dispatcher).dispatchForArticle(202L);
    }

    @Test
    @DisplayName("日上限熔断: todayCount 达 dailyMax → MAX_REACHED 中断, 不下发任何任务")
    void maxReachedSkipsDispatch() {
        when(quotaGuard.getTodayCount()).thenReturn(properties.getDailyMaxArticles());
        when(embeddingRepository.findPendingArticleIds(anyInt(), anyLong()))
                .thenReturn(List.of(1L, 2L, 3L));

        task.cronRun();

        verify(dispatcher, never()).dispatchForArticle(anyLong());
    }

    @Test
    @DisplayName("致命熔断: quotaGuard fatal → 整轮跳过")
    void fatalStopsRun() {
        when(quotaGuard.isFatal()).thenReturn(true);

        task.cronRun();

        verify(embeddingRepository, never()).findPendingArticleIds(anyInt(), anyLong());
        verify(dispatcher, never()).dispatchForArticle(anyLong());
    }
}
