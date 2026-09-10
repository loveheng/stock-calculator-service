package com.zzh.stock_calculator.announcement.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 订阅创建事件（设计文档 D13）：仅通知「有新订阅」，抓取编排细节由监听器承担；
 * orgId 可空（订阅时未必已知，监听器侧自行解析回填）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SubscriptionCreatedEvent {

    private String stockId;

    private String orgId;
}
