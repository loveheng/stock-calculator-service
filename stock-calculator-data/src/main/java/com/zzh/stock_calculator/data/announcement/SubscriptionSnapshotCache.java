package com.zzh.stock_calculator.data.announcement;

import com.zzh.stockcalc.contract.message.SubscriptionSnapshotPayload;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 订阅快照本地缓存（设计文档 R3，§8 阶段 4 任务 2）：collector 无 DB（D2），
 * 订阅全集以「version + stocks」内存态持有，由 control.subscription.snapshot 覆盖式更新。
 * version 单调（epoch millis）：拒绝不大于当前版本的快照，防旧消息回灌/乱序。
 */
@Component
@ConditionalOnProperty(prefix = "datasvc.collector", name = "enabled", havingValue = "true")
public class SubscriptionSnapshotCache {

    /** 启动初态：version=0 空集（首帧快照到达前不采集） */
    private static final SnapshotState EMPTY = new SnapshotState(0L, List.of());

    private volatile SnapshotState current = EMPTY;

    /**
     * 覆盖式更新（version 单调校验）。
     * @return true=已采纳；false=版本过期被拒绝（R3）
     */
    public synchronized boolean update(long version, List<SubscriptionSnapshotPayload.SnapshotStock> stocks) {
        if (version <= current.version()) {
            return false;
        }
        current = new SnapshotState(version, stocks == null ? List.of() : List.copyOf(stocks));
        return true;
    }

    public SnapshotState get() {
        return current;
    }

    /** 当前快照状态：version + 订阅标的全集 */
    public record SnapshotState(long version, List<SubscriptionSnapshotPayload.SnapshotStock> stocks) {
    }
}
