package com.zzh.stock_calculator.announcement.task;

import com.zzh.stock_calculator.announcement.config.AnnouncementProperties;
import com.zzh.stock_calculator.announcement.repository.AnnouncementSubscriptionRepository;
import com.zzh.stock_calculator.announcement.service.AnnouncementCollectService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.util.List;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 采集双路径门控单测（设计文档 §8 阶段 4 任务 2）：datasvc.mq.enabled=true 时
 * 本地同步任务空转（采集移交数据服务），不再查订阅表、不再触发采集。
 */
@ExtendWith(MockitoExtension.class)
class AnnouncementSyncTaskGateTest {

    @Mock
    private AnnouncementSubscriptionRepository subscriptionRepository;
    @Mock
    private AnnouncementCollectService collectService;

    /** 构造任务并注入 mqEnabled（@Value 字段，测试手填） */
    private AnnouncementSyncTask task(boolean mqEnabled) throws Exception {
        AnnouncementProperties properties = new AnnouncementProperties();
        properties.getSync().setEnabled(true);
        AnnouncementSyncTask task = new AnnouncementSyncTask(
                properties, subscriptionRepository, collectService);
        Field mq = AnnouncementSyncTask.class.getDeclaredField("mqEnabled");
        mq.setAccessible(true);
        mq.setBoolean(task, mqEnabled);
        return task;
    }

    @Test
    void mqEnabledSkipsLocalSync() throws Exception {
        task(true).sync();

        verifyNoInteractions(subscriptionRepository);
        verifyNoInteractions(collectService);
    }

    @Test
    void mqDisabledRunsLocalSync() throws Exception {
        when(subscriptionRepository.findDistinctStockIds()).thenReturn(List.of());

        task(false).sync();

        verify(subscriptionRepository).findDistinctStockIds();
        verify(collectService, never()).collectForStock(anyString(), anyString());
    }

    @Test
    void syncDisabledSkipsEverythingRegardless() throws Exception {
        AnnouncementProperties properties = new AnnouncementProperties();
        properties.getSync().setEnabled(false);
        AnnouncementSyncTask task = new AnnouncementSyncTask(
                properties, subscriptionRepository, collectService);
        Field mq = AnnouncementSyncTask.class.getDeclaredField("mqEnabled");
        mq.setAccessible(true);
        mq.setBoolean(task, false);

        task.sync();

        verifyNoInteractions(subscriptionRepository);
        verifyNoInteractions(collectService);
    }
}
