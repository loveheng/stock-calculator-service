package com.zzh.stock_calculator.announcement.task;

import com.zzh.stock_calculator.announcement.mq.AnnouncementProcessPublisher;
import com.zzh.stock_calculator.announcement.service.AnnouncementProcessService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * AnnouncementProcessTask 双路径分流（设计文档 §8 阶段 4 任务 3）：
 * datasvc.mq.enabled=true → 发布端 publishPendingBatch（本地处理空转）；
 * false（默认）→ 原进程内 processNextBatch 不变。
 */
@ExtendWith(MockitoExtension.class)
class AnnouncementProcessTaskPathTest {

    @Mock
    private AnnouncementProcessService processService;
    @Mock
    private ObjectProvider<AnnouncementProcessPublisher> publisherProvider;
    @Mock
    private AnnouncementProcessPublisher publisher;

    private AnnouncementProcessTask task;

    @BeforeEach
    void setUp() {
        task = new AnnouncementProcessTask(processService, publisherProvider);
    }

    @Test
    @DisplayName("MQ 开 → 扫描发布（处理移交 worker），本地批处理不执行")
    void mqEnabledPublishesInsteadOfLocalBatch() {
        ReflectionTestUtils.setField(task, "mqEnabled", true);
        when(publisherProvider.getIfAvailable()).thenReturn(publisher);

        task.process();

        verify(publisher).publishPendingBatch();
        verifyNoInteractions(processService);
    }

    @Test
    @DisplayName("MQ 关（默认）→ 原进程内路径不变")
    void mqDisabledKeepsInProcessPath() {
        ReflectionTestUtils.setField(task, "mqEnabled", false);

        task.process();

        verify(processService).processNextBatch();
        verifyNoInteractions(publisherProvider);
    }
}
