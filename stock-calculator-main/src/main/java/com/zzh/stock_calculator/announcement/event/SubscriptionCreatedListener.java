package com.zzh.stock_calculator.announcement.event;

import com.zzh.stock_calculator.announcement.config.AnnouncementProperties;
import com.zzh.stock_calculator.announcement.service.AnnouncementCollectService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 订阅触发抓取（D13；ArticleEmbeddingListener 同款 AFTER_COMMIT+@Async 模式）。
 * sync.enabled=false 时仅记日志跳过（cron 轮询兜底自愈）；抓取失败只 log 不回滚订阅
 * （订阅是权限事实，抓取是数据同步，失败由 cron 与下轮水位推导自愈）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SubscriptionCreatedListener {

    private final AnnouncementProperties properties;
    private final AnnouncementCollectService collectService;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSubscriptionCreated(SubscriptionCreatedEvent event) {
        if (!properties.getSync().isEnabled()) {
            log.info("announcement.sync.enabled=false，跳过订阅触发抓取（stockId={}），等 cron 兜底", event.getStockId());
            return;
        }
        try {
            collectService.collectForStock(event.getStockId(), event.getOrgId());
        } catch (Exception e) {
            log.warn("订阅触发抓取失败 stockId={}: {}", event.getStockId(), e.getMessage());
        }
    }
}
