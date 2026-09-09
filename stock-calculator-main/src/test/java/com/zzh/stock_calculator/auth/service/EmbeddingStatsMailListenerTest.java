package com.zzh.stock_calculator.auth.service;

import com.zzh.stock_calculator.crawler.EmbeddingStatsReportEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * EmbeddingStatsMailListener 单测（Mockito，无 Spring 上下文）：
 * 邮箱门控/MailService 兜底跳过、进行中完整模板、存量完成仅增量模板、发送失败吞掉。
 */
@ExtendWith(MockitoExtension.class)
class EmbeddingStatsMailListenerTest {

    private static final String EMAIL = "user@example.com";

    @Mock
    private ObjectProvider<MailService> mailServiceProvider;

    @Mock
    private MailService mailService;

    private EmbeddingStatsMailListener listener;

    @BeforeEach
    void setUp() {
        listener = new EmbeddingStatsMailListener(mailServiceProvider);
        ReflectionTestUtils.setField(listener, "notifyEmail", EMAIL);
    }

    private EmbeddingStatsReportEvent event(boolean backfillComplete) {
        return EmbeddingStatsReportEvent.builder()
                .windowStartEpochSecond(1757400000L)
                .windowEndEpochSecond(1757660000L)
                .newArticleCount(120)
                .newPendingCount(8)
                .totalArticles(1000)
                .doneCount(300)
                .failedCount(2)
                .embeddedInWindow(50)
                .backfillComplete(backfillComplete)
                .build();
    }

    @Test
    @DisplayName("EMBEDDING_NOTIFY_EMAIL 未配置 → 跳过发送")
    void missingEmailSkips() {
        ReflectionTestUtils.setField(listener, "notifyEmail", "");

        listener.onStatsReport(event(false));

        verify(mailServiceProvider, never()).getIfAvailable();
        verify(mailService, never()).sendText(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("MailService 缺失（app.auth.enabled=false）→ 兜底跳过")
    void missingMailServiceSkips() {
        when(mailServiceProvider.getIfAvailable()).thenReturn(null);

        assertThatCode(() -> listener.onStatsReport(event(false))).doesNotThrowAnyException();

        verify(mailService, never()).sendText(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("存量进行中 → 发送完整模板（含存量进度与新增数据两段）")
    void progressEventSendsFullTemplate() {
        when(mailServiceProvider.getIfAvailable()).thenReturn(mailService);

        listener.onStatsReport(event(false));

        verify(mailService).sendText(anyString(), contains("向量化统计报告"),
                contains("存量回填进度"));
        verify(mailService).sendText(anyString(), anyString(),
                contains("已向量化：300（30.0%）"));
        verify(mailService).sendText(anyString(), anyString(), contains("本周期新增电报：120 条"));
    }

    @Test
    @DisplayName("存量已完成 → 仅增量模板，不再输出存量进度段")
    void completeEventSendsIncrementalOnly() {
        when(mailServiceProvider.getIfAvailable()).thenReturn(mailService);

        listener.onStatsReport(event(true));

        verify(mailService).sendText(anyString(), anyString(), contains("仅统计增量数据"));
        verify(mailService).sendText(anyString(), anyString(), contains("新增电报：120 条"));
        verify(mailService, never()).sendText(anyString(), anyString(), contains("存量回填进度"));
    }

    @Test
    @DisplayName("发送失败 → 吞掉异常仅 WARN，不影响统计任务")
    void sendFailureSwallowed() {
        when(mailServiceProvider.getIfAvailable()).thenReturn(mailService);
        doThrow(new IllegalStateException("smtp down")).when(mailService)
                .sendText(anyString(), anyString(), anyString());

        assertThatCode(() -> listener.onStatsReport(event(false))).doesNotThrowAnyException();
    }
}
