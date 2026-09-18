package com.zzh.stock_calculator.announcement.task;

import com.zzh.stock_calculator.announcement.mq.AnnouncementProcessPublisher;
import com.zzh.stock_calculator.monitor.AppTaskHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 公告批处理定时任务（MQ 单路径终态）：PENDING 扫描发布 task.announcement.process，
 * 处理由数据服务 worker 承担（对账语义：未终态每轮重发）。
 * 调度由 pull_task_config CALENDAR 行表驱动（CalendarTaskClaimScheduler 认领 job.announcement.process）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AnnouncementProcessTask implements AppTaskHandler {

    private final AnnouncementProcessPublisher publisher;

    @Override
    public String taskCode() {
        return AppTaskHandler.TASK_ANNOUNCEMENT_PROCESS;
    }

    @Override
    public void run() {
        publisher.publishPendingBatch();
    }
}
