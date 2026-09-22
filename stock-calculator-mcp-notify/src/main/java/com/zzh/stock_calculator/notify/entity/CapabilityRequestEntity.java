package com.zzh.stock_calculator.notify.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

/**
 * 能力请求在途关联表实体（stock_mcp 库 capability_request 表，docs/notify/design.md §六）：
 * capability 直通发出时登记（traceId/reminderId/deadline），结果回流按 traceId 匹配；
 * deadline 到未回流 → 看门狗降级通知「数据暂不可用」（不做无限等待），pending 行清理。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "capability_request", indexes = {
        @Index(name = "idx_capability_request_deadline", columnList = "deadline")
})
public class CapabilityRequestEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 关联键：请求信封与结果信封共用同一 traceId */
    @Column(name = "trace_id", nullable = false, length = 64)
    private String traceId;

    /** 发起方 reminder（结果回流后状态校验，done/cancelled 则丢弃） */
    @Column(name = "reminder_id", nullable = false)
    private Long reminderId;

    /** 超时死线：到期未回流由看门狗降级 */
    @Column(name = "deadline", nullable = false)
    private OffsetDateTime deadline;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
}
