package com.zzh.stock_calculator.announcement.task;

import com.zzh.stock_calculator.announcement.config.AnnouncementProperties;
import com.zzh.stock_calculator.announcement.entity.AnnouncementSubscription;
import com.zzh.stock_calculator.announcement.repository.AnnouncementSubscriptionRepository;
import com.zzh.stock_calculator.announcement.service.AnnouncementCollectService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 公告同步定时任务（设计文档 §4.1/§7）：小时级轮询订阅标的（或种子标的），
 * 单标的异常隔离，不中断整批。enabled=false 时空转。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AnnouncementSyncTask {

    private final AnnouncementProperties properties;
    private final AnnouncementSubscriptionRepository subscriptionRepository;
    private final AnnouncementCollectService collectService;

    @Scheduled(cron = "${announcement.sync.cron:0 0 * * * *}")
    public void sync() {
        if (!properties.getSync().isEnabled()) {
            return;
        }
        List<String> stockIds = subscriptionRepository.findDistinctStockIds();
        if (stockIds.isEmpty()) {
            // 种子标的兜底："code" 或 "code,orgId"（逗号分割取首段）
            stockIds = properties.getSync().getSeedStockList().stream()
                    .map(entry -> entry.split(",", 2)[0])
                    .toList();
        }
        for (String stockId : stockIds) {
            String orgId = subscriptionRepository.findFirstByStockIdOrderByCreatedAtAsc(stockId)
                    .map(AnnouncementSubscription::getOrgId)
                    .orElse(null);
            try {
                collectService.collectForStock(stockId, orgId);
            } catch (Exception e) {
                log.warn("公告同步失败（单标的隔离）stockId={}: {}", stockId, e.getMessage());
            }
        }
    }
}
