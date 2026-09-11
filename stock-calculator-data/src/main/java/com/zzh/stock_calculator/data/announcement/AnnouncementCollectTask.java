package com.zzh.stock_calculator.data.announcement;

import com.zzh.stockcalc.contract.message.SubscriptionSnapshotPayload;
import com.zzh.stock_calculator.data.announcement.SubscriptionSnapshotCache.SnapshotState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 公告定时采集（设计文档 §8 阶段 4 任务 2，D4）：collector 副本恒=1，
 * 以本地快照缓存为标的源（首帧快照到达前缓存为空 → 空转）。
 * 双重门控：datasvc.collector.enabled（角色）× announcement.enabled（域开关，
 * 防未显式配置的进程打真实 CNINFO）。单标的异常隔离，不中断整批。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "datasvc.collector", name = "enabled", havingValue = "true")
@ConditionalOnProperty(prefix = "datasvc.collector.announcement", name = "enabled", havingValue = "true")
public class AnnouncementCollectTask {

    private final SubscriptionSnapshotCache snapshotCache;
    private final AnnouncementCollectorService collectorService;

    @Scheduled(cron = "${datasvc.collector.announcement.cron:0 5 * * * *}")
    public void collect() {
        SnapshotState snapshot = snapshotCache.get();
        if (snapshot.stocks().isEmpty()) {
            log.info("订阅快照缓存为空（version={}），本轮公告采集空转", snapshot.version());
            return;
        }
        int published = 0;
        for (SubscriptionSnapshotPayload.SnapshotStock stock : snapshot.stocks()) {
            try {
                published += collectorService.collectStock(stock);
            } catch (Exception e) {
                log.warn("公告采集失败（单标的隔离）stockId={}: {}", stock.getStockId(), e.getMessage());
            }
        }
        log.info("公告采集轮次结束 version={} stocks={} published={}",
                snapshot.version(), snapshot.stocks().size(), published);
    }
}
