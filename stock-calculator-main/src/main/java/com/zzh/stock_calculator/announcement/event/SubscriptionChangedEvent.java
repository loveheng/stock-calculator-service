package com.zzh.stock_calculator.announcement.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 订阅变更事件（设计文档 §8 阶段 4/R3）：subscribe/unsubscribe 均在事务内发布，
 * 快照发布器 AFTER_COMMIT 消费后重推全量快照（覆盖式语义，事件只当触发器，
 * 快照内容以提交后的 DB 状态为准，消费侧不读事件字段防读到未提交态）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SubscriptionChangedEvent {

    /** 触发本次变更的标的（仅日志排障用，快照仍为全量） */
    private String stockId;
}
