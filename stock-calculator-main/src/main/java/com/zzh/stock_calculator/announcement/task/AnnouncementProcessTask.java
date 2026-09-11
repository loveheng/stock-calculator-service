package com.zzh.stock_calculator.announcement.task;

import com.zzh.stock_calculator.announcement.mq.AnnouncementProcessPublisher;
import com.zzh.stock_calculator.announcement.service.AnnouncementProcessService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 公告批处理定时任务（设计文档 §4.5，§8 阶段 4 任务 3 双路径）：
 * MQ 开（datasvc.mq.enabled=true）→ PENDING 扫描发布 task.announcement.process
 * （处理移交数据服务 worker，对账语义：未终态每轮重发）；MQ 关（默认）→ 原进程内
 * 路径不变（消费 PENDING 队列，近端优先 50 条/批）。enabled=false 两条路径均空转。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AnnouncementProcessTask {

    private final AnnouncementProcessService processService;
    /** MQ 发布端（同开关装配；ObjectProvider 防实现缺失阻启动） */
    private final ObjectProvider<AnnouncementProcessPublisher> publisherProvider;

    /** 处理双路径门控（§8 阶段 4）：MQ 开 → 处理由数据服务 worker 承担 */
    @Value("${datasvc.mq.enabled:false}")
    private boolean mqEnabled;

    @Scheduled(cron = "${announcement.process.cron:0 1 * * * *}")
    public void process() {
        if (mqEnabled) {
            AnnouncementProcessPublisher publisher = publisherProvider.getIfAvailable();
            if (publisher == null) {
                log.warn("datasvc.mq.enabled=true 但公告任务发布器未装配，本轮跳过");
                return;
            }
            publisher.publishPendingBatch();
            return;
        }
        int processed = processService.processNextBatch();
        if (processed > 0) {
            log.info("公告批处理完成 count={}", processed);
        }
    }
}
