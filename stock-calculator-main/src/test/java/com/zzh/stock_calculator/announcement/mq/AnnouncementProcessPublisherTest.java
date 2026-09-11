package com.zzh.stock_calculator.announcement.mq;

import com.zzh.stock_calculator.announcement.config.AnnouncementProperties;
import com.zzh.stock_calculator.announcement.entity.Announcement;
import com.zzh.stock_calculator.announcement.entity.AnnouncementStatus;
import com.zzh.stock_calculator.announcement.repository.AnnouncementRepository;
import com.zzh.stock_calculator.crawler.AnnouncementEmbeddingApi;
import com.zzh.stock_calculator.crawler.TaskDispatchApi;
import com.zzh.stockcalc.contract.MessageType;
import com.zzh.stockcalc.contract.message.AnnouncementProcessTask;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * AnnouncementProcessPublisher 单测（Mockito）：PENDING 扫描→发布两级分发
 * （待蒸馏→process 任务 / 已蒸馏→二段向量化补发）、发布端熔断窗口（RATE_LIMITED
 * 冷却）、process.enabled 总开关、发布失败不计数（D6 对账兜底）。
 */
@ExtendWith(MockitoExtension.class)
class AnnouncementProcessPublisherTest {

    @Mock
    private AnnouncementRepository announcementRepository;
    @Mock
    private TaskDispatchApi taskDispatchApi;
    @Mock
    private ObjectProvider<AnnouncementEmbeddingApi> embeddingApiProvider;
    @Mock
    private AnnouncementEmbeddingApi embeddingApi;

    private AnnouncementProperties properties;

    private AnnouncementProcessPublisher publisher;

    @BeforeEach
    void setUp() {
        properties = new AnnouncementProperties();
        properties.getProcess().setEnabled(true);
        publisher = new AnnouncementProcessPublisher(
                announcementRepository, taskDispatchApi, properties, embeddingApiProvider);
    }

    private Announcement pendingRow(long id, String announcementId, String summary) {
        Announcement row = Announcement.builder()
                .announcementId(announcementId).title("标题").adjunctUrl("u.pdf").secCode("990002")
                .status(AnnouncementStatus.PENDING).summary(summary)
                .build();
        row.setId(id);
        return row;
    }

    @Test
    @DisplayName("待蒸馏 PENDING → task.announcement.process（元数据四件套，不含 PDF）")
    void dispatchesProcessTasksForDistillationPending() {
        when(announcementRepository.findTop50ByStatusAndSummaryIsNullOrderBySeDateDescIdDesc(
                AnnouncementStatus.PENDING)).thenReturn(List.of(
                pendingRow(1L, "ann-1", null), pendingRow(2L, "ann-2", null)));
        when(taskDispatchApi.dispatchTask(eq(MessageType.TASK_ANNOUNCEMENT_PROCESS), any()))
                .thenReturn(true);

        int dispatched = publisher.publishPendingBatch();

        assertThat(dispatched).isEqualTo(2);
        ArgumentCaptor<Object> payloadCaptor = ArgumentCaptor.forClass(Object.class);
        verify(taskDispatchApi, times(2))
                .dispatchTask(eq(MessageType.TASK_ANNOUNCEMENT_PROCESS), payloadCaptor.capture());
        AnnouncementProcessTask task = (AnnouncementProcessTask) payloadCaptor.getAllValues().get(0);
        assertThat(task.getAnnouncementId()).isEqualTo("ann-1");
        assertThat(task.getTitle()).isEqualTo("标题");
        assertThat(task.getAdjunctUrl()).isEqualTo("u.pdf");
        assertThat(task.getSecCode()).isEqualTo("990002");
        verify(embeddingApi, never()).dispatchEmbeddingTask(any(), anyBoolean());
    }

    @Test
    @DisplayName("已蒸馏未向量化 PENDING → 二段向量化任务补发（避免 worker 全量重处理）")
    void dispatchesEmbeddingTasksForResumePending() {
        when(announcementRepository.findTop50ByStatusAndSummaryIsNullOrderBySeDateDescIdDesc(
                AnnouncementStatus.PENDING)).thenReturn(List.of());
        when(announcementRepository.findByStatusAndSummaryNotNull(AnnouncementStatus.PENDING))
                .thenReturn(List.of(pendingRow(7L, "ann-7", "摘要")));
        when(embeddingApiProvider.getIfAvailable()).thenReturn(embeddingApi);
        when(embeddingApi.dispatchEmbeddingTask(7L, false)).thenReturn(true);

        int dispatched = publisher.publishPendingBatch();

        assertThat(dispatched).isEqualTo(1);
        verify(embeddingApi).dispatchEmbeddingTask(7L, false);
        verify(taskDispatchApi, never()).dispatchTask(eq(MessageType.TASK_ANNOUNCEMENT_PROCESS), any());
    }

    @Test
    @DisplayName("发布失败不计数：dispatch 返回 false 不计入（对账下轮重发）")
    void failedDispatchNotCounted() {
        when(announcementRepository.findTop50ByStatusAndSummaryIsNullOrderBySeDateDescIdDesc(
                AnnouncementStatus.PENDING)).thenReturn(List.of(pendingRow(1L, "ann-1", null)));
        when(taskDispatchApi.dispatchTask(eq(MessageType.TASK_ANNOUNCEMENT_PROCESS), any()))
                .thenReturn(false);

        assertThat(publisher.publishPendingBatch()).isZero();
    }

    @Test
    @DisplayName("RATE_LIMITED 熔断：冷却窗口内跳过发布，窗口外自动恢复")
    void rateLimitCooldownBlocksAndRecovers() {
        properties.getProcess().setRateLimitCooldownMinutes(-1L); // 负值 = 窗口已过期（测试时钟免等待）
        publisher.markRateLimited();
        when(announcementRepository.findTop50ByStatusAndSummaryIsNullOrderBySeDateDescIdDesc(
                AnnouncementStatus.PENDING)).thenReturn(List.of());

        assertThat(publisher.publishPendingBatch()).isZero();
        verifyNoInteractions(taskDispatchApi);

        properties.getProcess().setRateLimitCooldownMinutes(30L);
        publisher.markRateLimited();
        assertThat(publisher.publishPendingBatch()).isZero();
        verifyNoInteractions(taskDispatchApi);
    }

    @Test
    @DisplayName("process.enabled=false → 总开关关闭空转")
    void disabledByProcessSwitch() {
        properties.getProcess().setEnabled(false);

        assertThat(publisher.publishPendingBatch()).isZero();
        verifyNoInteractions(announcementRepository, taskDispatchApi);
    }
}
