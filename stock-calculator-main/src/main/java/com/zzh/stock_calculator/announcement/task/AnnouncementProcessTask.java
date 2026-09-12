package com.zzh.stock_calculator.announcement.task;

import com.zzh.stock_calculator.announcement.mq.AnnouncementProcessPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 公告批处理定时任务（MQ 单路径终态）：PENDING 扫描发布 task.announcement.process，
 * 处理由数据服务 worker 承担（对账语义：未终态每轮重发）。发布端开关关闭时空转。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AnnouncementProcessTask {

    private final AnnouncementProcessPublisher publisher;

    @Scheduled(cron = "${announcement.process.cron:0 1 * * * *}")
    public void process() {
        publisher.publishPendingBatch();
    }
}
