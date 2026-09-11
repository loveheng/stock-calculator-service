package com.zzh.stock_calculator.announcement.mq;

import com.zzh.stock_calculator.announcement.entity.AnnouncementSubscription;
import com.zzh.stock_calculator.announcement.event.SubscriptionChangedEvent;
import com.zzh.stock_calculator.announcement.repository.AnnouncementRepository;
import com.zzh.stock_calculator.announcement.repository.AnnouncementSubscriptionRepository;
import com.zzh.stock_calculator.crawler.TaskDispatchApi;
import com.zzh.stockcalc.contract.MessageType;
import com.zzh.stockcalc.contract.message.SubscriptionSnapshotPayload;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 订阅快照发布器（设计文档 §4.3/R3，§8 阶段 4 任务 1）：主服务 → collector 的
 * control.subscription.snapshot 下发。覆盖式全量语义——每次触发都重读 DB 构建
 * 完整快照（空订阅 = 空列表照发，collector 清缓存停采集），事件只当触发器。
 * <p>三触发点：①订阅变更 AFTER_COMMIT（事务内发布，提交后才读 DB 防未提交态）；
 * ②定时重推（兜底快照丢失，R6）；③启动首推（collector 晚启动也不丢全集）。
 * version = epoch millis 单调，collector 拒旧版本（R3 防缓存漂移）。</p>
 * <p>门控：datasvc.mq.enabled=false 不装配，订阅本地采集回退路径不受影响。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "datasvc.mq", name = "enabled", havingValue = "true")
public class SubscriptionSnapshotPublisher {

    /** 增量水位重叠天数（与 AnnouncementCollectService.OVERLAP_DAYS 同语义，D3） */
    private static final int OVERLAP_DAYS = 7;

    private final TaskDispatchApi taskDispatchApi;
    private final AnnouncementSubscriptionRepository subscriptionRepository;
    private final AnnouncementRepository announcementRepository;

    /** 订阅变更触发（subscribe/unsubscribe 事务提交后） */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSubscriptionChanged(SubscriptionChangedEvent event) {
        log.info("订阅变更触发快照重推 stockId={}", event.getStockId());
        publishSnapshot();
    }

    /** 启动首推：快照推到队列即持久，collector 晚启动不丢 */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        publishSnapshot();
    }

    /** 定时重推：兜底消息丢失/collector 重启（announcement.snapshot.cron，默认 30min） */
    @Scheduled(cron = "${announcement.snapshot.cron:0 */30 * * * *}")
    public void scheduledRepublish() {
        publishSnapshot();
    }

    /** 重读 DB 构建全量快照并下发（MQ 未启用时空转，返回 false） */
    public boolean publishSnapshot() {
        long version = System.currentTimeMillis();
        List<SubscriptionSnapshotPayload.SnapshotStock> stocks = new ArrayList<>();
        for (String stockId : subscriptionRepository.findDistinctStockIds()) {
            stocks.add(SubscriptionSnapshotPayload.SnapshotStock.builder()
                    .stockId(stockId)
                    .orgId(resolveOrgId(stockId))
                    .since(resolveSince(stockId))
                    .build());
        }
        boolean dispatched = taskDispatchApi.dispatchControl(MessageType.CONTROL_SUBSCRIPTION_SNAPSHOT,
                SubscriptionSnapshotPayload.builder().version(version).stocks(stocks).build());
        log.info("订阅快照{} version={} stocks={}", dispatched ? "已下发" : "未下发（MQ 关闭）", version, stocks.size());
        return dispatched;
    }

    /** orgId 取最早订阅行存量（采集期回填锚点）；空 = collector 自解析 */
    private String resolveOrgId(String stockId) {
        return subscriptionRepository.findFirstByStockIdOrderByCreatedAtAsc(stockId)
                .map(AnnouncementSubscription::getOrgId)
                .orElse(null);
    }

    /** 水位提示：最新公告日-7 天重叠窗口（ISO 文本）；无存量/null 公告日 = null（首拉由 collector 配置推导） */
    private String resolveSince(String stockId) {
        Optional<LocalDate> latest = announcementRepository
                .findFirstBySecCodeOrderBySeDateDesc(stockId)
                .map(announcement -> announcement.getSeDate());
        return latest.map(date -> date.minusDays(OVERLAP_DAYS).toString()).orElse(null);
    }
}
