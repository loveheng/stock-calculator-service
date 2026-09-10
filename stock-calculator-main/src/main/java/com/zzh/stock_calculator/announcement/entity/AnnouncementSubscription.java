package com.zzh.stock_calculator.announcement.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 公告订阅（设计文档 §3/D13）：用户×股票权限；订阅即触发抓取事件，
 * 已有订阅不重复抓取；退订不删公告（历史数据保留）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "announcement_subscription",
        uniqueConstraints = @UniqueConstraint(name = "uk_ann_sub_user_stock", columnNames = {"user_id", "stock_id"}),
        indexes = @Index(name = "idx_ann_sub_stock", columnList = "stock_id"))
public class AnnouncementSubscription {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** auth 用户 UUID（users.id） */
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /** 股票代码（6 位数字文本） */
    @Column(name = "stock_id", nullable = false, length = 32)
    private String stockId;

    /** CNINFO orgId（订阅时未必已知，采集期 topSearch 回填） */
    @Column(name = "org_id", length = 64)
    private String orgId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, columnDefinition = "timestamptz DEFAULT now()")
    private OffsetDateTime createdAt;
}
