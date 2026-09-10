package com.zzh.stock_calculator.announcement.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 订阅端点 DTO（announcement 域）。
 */
public class SubscriptionDtos {

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class SubscribeRequest {
        /** 股票代码（6 位数字文本） */
        private String stockId;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class SubscriptionItem {
        private String stockId;
        private String orgId;
        private OffsetDateTime createdAt;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class SubscriptionListResponse {
        private List<SubscriptionItem> items;
    }
}
