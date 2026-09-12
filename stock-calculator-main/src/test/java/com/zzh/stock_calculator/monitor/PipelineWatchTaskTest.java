package com.zzh.stock_calculator.monitor;

import com.zzh.stockcalc.contract.MqQueue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;

import java.sql.ResultSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PipelineWatchTask 巡检单测（Mockito，无 Spring 上下文）：
 * 消费端消失 → DATA_DOWN、积压 → BACKLOG、死信 → DEAD_Q、管理 API 不可达 →
 * BROKER_UNREACHABLE、冷却去重、健康态零告警。
 */
@ExtendWith(MockitoExtension.class)
class PipelineWatchTaskTest {

    @Mock
    private RabbitManagementClient managementClient;

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private PipelineWatchTask task;

    @BeforeEach
    void setUp() {
        PipelineWatchProperties properties = new PipelineWatchProperties();
        properties.setQueueBacklogThreshold(100);
        task = new PipelineWatchTask(managementClient, jdbcTemplate, properties, eventPublisher);
    }

    private RabbitManagementClient.QueueStat stat(String name, int messages, int consumers) {
        return new RabbitManagementClient.QueueStat(name, messages, consumers);
    }

    private void mockDbPendingIdle() {
        org.mockito.Mockito.doAnswer(invocation -> {
            RowCallbackHandler handler = invocation.getArgument(1);
            ResultSet rs = mock(ResultSet.class);
            when(rs.next()).thenReturn(true);
            when(rs.getLong(1)).thenReturn(0L);
            when(rs.getLong(2)).thenReturn(0L);
            handler.processRow(rs);
            return null;
        }).when(jdbcTemplate).query(anyString(), any(RowCallbackHandler.class));
    }

    @Test
    @DisplayName("健康态：consumer 正常/无积压/无死信 → 零告警")
    void healthyClusterPublishesNothing() {
        when(managementClient.fetchQueueStats()).thenReturn(List.of(
                stat(MqQueue.TASK_ANNOUNCEMENT_PROCESS, 0, 1),
                stat(MqQueue.TASK_EMBEDDING_COMPUTE, 5, 1),
                stat(MqQueue.RESULT_INGEST, 0, 1),
                stat(MqQueue.DEAD, 0, 0)));
        mockDbPendingIdle();

        task.watch();

        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("消费端消失：任务队列 consumer=0 → DATA_DOWN 告警")
    void zeroConsumersAlertsDataDown() {
        when(managementClient.fetchQueueStats()).thenReturn(List.of(
                stat(MqQueue.TASK_ANNOUNCEMENT_PROCESS, 12, 0),
                stat(MqQueue.TASK_EMBEDDING_COMPUTE, 0, 1),
                stat(MqQueue.RESULT_INGEST, 0, 1),
                stat(MqQueue.DEAD, 0, 0)));
        mockDbPendingIdle();

        task.watch();

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(captor.capture());
        PipelineAlertEvent event = (PipelineAlertEvent) captor.getValue();
        assertThat(event.getKind()).isEqualTo(PipelineWatchTask.KIND_DATA_DOWN + ":" + MqQueue.TASK_ANNOUNCEMENT_PROCESS);
        assertThat(event.getSubject()).contains("数据服务消费端消失");
    }

    @Test
    @DisplayName("同类告警冷却：连续两轮 DATA_DOWN 只发一封")
    void cooldownSuppressesRepeatAlert() {
        when(managementClient.fetchQueueStats()).thenReturn(List.of(
                stat(MqQueue.TASK_ANNOUNCEMENT_PROCESS, 12, 0),
                stat(MqQueue.TASK_EMBEDDING_COMPUTE, 0, 1),
                stat(MqQueue.RESULT_INGEST, 0, 1),
                stat(MqQueue.DEAD, 0, 0)));
        mockDbPendingIdle();

        task.watch();
        task.watch();

        verify(eventPublisher, times(1)).publishEvent(any(PipelineAlertEvent.class));
    }

    @Test
    @DisplayName("积压：消息数超阈值 → BACKLOG 告警；死信有存量 → DEAD_Q 告警")
    void backlogAndDeadLetterAlert() {
        when(managementClient.fetchQueueStats()).thenReturn(List.of(
                stat(MqQueue.TASK_EMBEDDING_COMPUTE, 150, 1),
                stat(MqQueue.RESULT_INGEST, 0, 1),
                stat(MqQueue.DEAD, 2, 0)));
        mockDbPendingIdle();

        task.watch();

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher, times(2)).publishEvent(captor.capture());
        List<Object> events = captor.getAllValues();
        assertThat(events).extracting(e -> ((PipelineAlertEvent) e).getKind())
                .containsExactlyInAnyOrder(
                        PipelineWatchTask.KIND_BACKLOG + ":" + MqQueue.TASK_EMBEDDING_COMPUTE,
                        PipelineWatchTask.KIND_DEAD_Q);
    }

    @Test
    @DisplayName("管理 API 不可达 → BROKER_UNREACHABLE 告警（fail-loud），不查队列")
    void brokerUnreachableAlerts() {
        when(managementClient.fetchQueueStats()).thenThrow(new IllegalStateException("connection refused"));

        task.watch();

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(((PipelineAlertEvent) captor.getValue()).getKind())
                .isEqualTo(PipelineWatchTask.KIND_BROKER_UNREACHABLE);
        verify(jdbcTemplate, never()).query(anyString(), any(RowCallbackHandler.class));
    }
}
