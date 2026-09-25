package com.zzh.stock_calculator.broker.entity;

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
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 画布经纪监控任务（free-canvas §3.5·B 调度路径 SSOT 修订）：
 * 用户级个股价格监控，状态机 RUNNING/STOPPED；判定节流与告警冷却见
 * last_checked_at / last_alert_at。表结构：postgres/schema.sql（ddl-auto=none）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "broker_monitor_task", indexes = {
        @Index(name = "idx_broker_monitor_due", columnList = "status, last_checked_at"),
        @Index(name = "idx_broker_monitor_user", columnList = "user_id, status")
})
public class BrokerMonitorTaskEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, length = 64)
    private String userId;

    /** 6 位字典码（归一化后入库，腾讯形态不入库） */
    @Column(name = "stock_code", nullable = false, length = 8)
    private String stockCode;

    @Column(name = "alert_type", nullable = false, length = 20)
    private String alertType;

    @Column(nullable = false, precision = 12, scale = 4)
    private BigDecimal threshold;

    @Column(nullable = false, length = 16)
    @Builder.Default
    private String status = "RUNNING";

    @Column(name = "last_checked_at")
    private OffsetDateTime lastCheckedAt;

    @Column(name = "last_alert_at")
    private OffsetDateTime lastAlertAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
