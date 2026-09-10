package com.zzh.stock_calculator.announcement.task;

import com.zzh.stock_calculator.announcement.service.AnnouncementProcessService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 公告批处理定时任务（设计文档 §4.5）：消费 PENDING 队列（近端优先 50 条/批）。
 * enabled=false 时空转；增量优先于历史积压（查询近端优先，§4.1 护栏②）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AnnouncementProcessTask {

    private final AnnouncementProcessService processService;

    @Scheduled(cron = "${announcement.process.cron:0 1 * * * *}")
    public void process() {
        int processed = processService.processNextBatch();
        if (processed > 0) {
            log.info("公告批处理完成 count={}", processed);
        }
    }
}
