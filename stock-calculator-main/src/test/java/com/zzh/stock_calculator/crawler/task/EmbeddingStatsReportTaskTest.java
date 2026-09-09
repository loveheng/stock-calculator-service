package com.zzh.stock_calculator.crawler.task;

import com.zzh.stock_calculator.crawler.EmbeddingStatsReportEvent;
import com.zzh.stock_calculator.crawler.embedding.config.EmbeddingGate;
import com.zzh.stock_calculator.crawler.embedding.config.EmbeddingProperties;
import com.zzh.stock_calculator.crawler.embedding.entity.EmbeddingStatus;
import com.zzh.stock_calculator.crawler.embedding.repository.ClsArticleEmbeddingRepository;
import com.zzh.stock_calculator.crawler.repository.ClsArticleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * EmbeddingStatsReportTask 编排逻辑单测（Mockito，无 Spring 上下文）：
 * 间隔判定（未满不发/满发一次）、enabled 开关、统计窗口与字段透传、
 * 存量完成判定（DONE + FAILED >= 总数）与完成后不重发。
 */
@ExtendWith(MockitoExtension.class)
class EmbeddingStatsReportTaskTest {

    @Mock
    private ClsArticleEmbeddingRepository embeddingRepository;

    @Mock
    private ClsArticleRepository articleRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private EmbeddingProperties properties;

    private EmbeddingStatsReportTask task;

    @BeforeEach
    void setUp() {
        properties = new EmbeddingProperties();
        // 门控开启态（R1）：enabled=true + 凭据齐备，等价生产配置
        properties.setEnabled(true);
        properties.getCloudflare().setAccountId("acc-test");
        properties.getCloudflare().setApiToken("tok-test");
        task = new EmbeddingStatsReportTask(embeddingRepository, articleRepository,
                properties, eventPublisher, new EmbeddingGate(properties));

        lenient().when(embeddingRepository.countArticles()).thenReturn(1000L);
        lenient().when(embeddingRepository.countDone()).thenReturn(300L);
        lenient().when(embeddingRepository.countByStatus(any())).thenReturn(0L);
        lenient().when(embeddingRepository.countByEmbeddedAtGreaterThanEqual(any(OffsetDateTime.class)))
                .thenReturn(50L);
        lenient().when(articleRepository.countByCtimeGreaterThanEqual(any())).thenReturn(120L);
        lenient().when(embeddingRepository.countNewPendingArticles(anyLong())).thenReturn(8L);
    }

    private static long today() {
        return Instant.now().getEpochSecond() / 86400L;
    }

    @Test
    @DisplayName("间隔未满 → 不发布")
    void intervalNotReachedSkips() {
        task.lastSentEpochDay.set(today());

        task.cronCheck();

        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("enabled=false → 不发布")
    void disabledSkips() {
        properties.getReport().setEnabled(false);
        task.lastSentEpochDay.set(today() - 10);

        task.cronCheck();

        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("门控关闭: embedding 未启用 → 统计跳过，不触仓储不发布")
    void gateClosedSkips() {
        properties.setEnabled(false);
        task.lastSentEpochDay.set(today() - 10);

        task.cronCheck();

        verify(embeddingRepository, never()).countArticles();
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("间隔满 → 发布事件，窗口与统计字段正确；lastSent 推进后同日不重发")
    void intervalReachedPublishesOnce() {
        task.lastSentEpochDay.set(today() - 3);

        task.cronCheck();

        ArgumentCaptor<EmbeddingStatsReportEvent> captor =
                ArgumentCaptor.forClass(EmbeddingStatsReportEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        EmbeddingStatsReportEvent event = captor.getValue();
        assertThat(event.getWindowStartEpochSecond()).isEqualTo((today() - 3) * 86400L);
        assertThat(event.getNewArticleCount()).isEqualTo(120L);
        assertThat(event.getNewPendingCount()).isEqualTo(8L);
        assertThat(event.getEmbeddedInWindow()).isEqualTo(50L);
        assertThat(event.getTotalArticles()).isEqualTo(1000L);
        assertThat(event.getDoneCount()).isEqualTo(300L);
        assertThat(event.getFailedCount()).isZero();
        assertThat(event.isBackfillComplete()).isFalse();

        // lastSent 已推进 → 同日再触发不重发
        task.cronCheck();
        verify(eventPublisher, times(1)).publishEvent(any(EmbeddingStatsReportEvent.class));
    }

    @Test
    @DisplayName("存量全量完成（DONE + FAILED >= 总数）→ backfillComplete=true")
    void completeWhenDonePlusFailedCoversTotal() {
        task.lastSentEpochDay.set(today() - 3);
        when(embeddingRepository.countDone()).thenReturn(995L);
        when(embeddingRepository.countByStatus(EmbeddingStatus.FAILED)).thenReturn(5L);

        task.cronCheck();

        ArgumentCaptor<EmbeddingStatsReportEvent> captor =
                ArgumentCaptor.forClass(EmbeddingStatsReportEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().isBackfillComplete()).isTrue();
    }

    @Test
    @DisplayName("窗口起点 = 上次发送时刻（epoch day * 86400 秒，UTC 口径）")
    void windowStartAlignsToLastSentDay() {
        long lastDay = today() - 6;
        task.lastSentEpochDay.set(lastDay);

        task.cronCheck();

        ArgumentCaptor<OffsetDateTime> sinceCaptor = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(embeddingRepository).countByEmbeddedAtGreaterThanEqual(sinceCaptor.capture());
        assertThat(sinceCaptor.getValue().toInstant())
                .isEqualTo(Instant.ofEpochSecond(lastDay * 86400L));
        verify(articleRepository).countByCtimeGreaterThanEqual(lastDay * 86400L);
        verify(embeddingRepository).countNewPendingArticles(lastDay * 86400L);
    }
}
