package com.zzh.stock_calculator.auth.service;

import com.zzh.stock_calculator.crawler.EmbeddingBackfillCompletedEvent;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 回填完成邮件监听器单测：收件人缺失/MailService 缺失优雅跳过，
 * 正常发送含统计摘要，发送失败吞异常不影响调用方。
 */
@ExtendWith(MockitoExtension.class)
class EmbeddingBackfillMailListenerTest {

    @Mock
    private ObjectProvider<MailService> mailServiceProvider;

    @Mock
    private MailService mailService;

    private EmbeddingBackfillMailListener listener;

    @BeforeEach
    void setUp() {
        listener = new EmbeddingBackfillMailListener(mailServiceProvider);
        ReflectionTestUtils.setField(listener, "notifyEmail", "ops@example.com");
    }

    private EmbeddingBackfillCompletedEvent event() {
        return EmbeddingBackfillCompletedEvent.builder()
                .totalArticles(500_000L)
                .doneCount(499_990L)
                .failedCount(10L)
                .elapsedMs(600_000L)
                .build();
    }

    @Test
    @DisplayName("配置了收件人 → 发送含统计摘要的文本邮件")
    void sendsSummaryMail() {
        when(mailServiceProvider.getIfAvailable()).thenReturn(mailService);

        listener.onBackfillCompleted(event());

        verify(mailService).sendText(eq("ops@example.com"), contains("回填"), contains("500000"));
    }

    @Test
    @DisplayName("收件人未配置 → 跳过，不触碰 MailService")
    void skipsWithoutRecipient() {
        ReflectionTestUtils.setField(listener, "notifyEmail", " ");

        listener.onBackfillCompleted(event());

        verifyNoInteractions(mailServiceProvider);
    }

    @Test
    @DisplayName("MailService 未装配（app.auth.enabled=false）→ 优雅跳过")
    void skipsWithoutMailService() {
        when(mailServiceProvider.getIfAvailable()).thenReturn(null);

        assertThatCode(() -> listener.onBackfillCompleted(event())).doesNotThrowAnyException();

        verifyNoInteractions(mailService);
    }

    @Test
    @DisplayName("发送失败 → 吞掉异常，不影响事件发布方")
    void swallowsSendFailure() {
        when(mailServiceProvider.getIfAvailable()).thenReturn(mailService);
        doThrow(new RuntimeException("smtp down"))
                .when(mailService).sendText(anyString(), anyString(), anyString());

        assertThatCode(() -> listener.onBackfillCompleted(event())).doesNotThrowAnyException();
    }
}
