package com.zzh.stockcalc.contract.message;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * control.subscription.snapshot 的 payload（设计文档 §4.3/R3）：
 * 主服务 → collector 的订阅快照，覆盖式语义——collector 以收到的 stocks 全量替换本地缓存，
 * version 单调（epoch millis）防旧快照回灌；快照丢失由 30min 定时重推兜底（D6）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SubscriptionSnapshotPayload {

    /** 快照版本（epoch millis，单调），collector 拒绝 version 不大于本地缓存值的快照 */
    private long version;

    /** 当前订阅标的全集（空列表 = 清空缓存，语义合法必须照发） */
    private List<SnapshotStock> stocks;

    /**
     * 单个订阅标的：stockId 六位代码；orgId CNINFO 机构 ID（订阅行存量，可空——
     * collector 侧 topSearch 自解析）；since 增量水位提示（该标的最新公告日-7 天重叠窗口，
     * ISO yyyy-MM-dd；null = 无存量公告，collector 按自身首拉配置推导）。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SnapshotStock {

        private String stockId;

        private String orgId;

        private String since;
    }
}
