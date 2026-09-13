package com.zzh.stock_calculator.monitor.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.OffsetDateTime;

/**
 * 常态拉取自循环心跳（docs/pull-loop-unification-design.md §3）：每拉取源一行 upsert，
 * 看门狗判活依据（last_renew_time 超期 → 补种）+ 仪表盘展示口径。心跳为观测信号，
 * 写入失败不影响循环（设计不变量 3）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "pull_heartbeat")
public class PullHeartbeatEntity {

    /** 拉取源标识（外部 ID，data 首次回报时落行） */
    @Id
    @Column(name = "task_code", length = 64, nullable = false)
    private String taskCode;

    /** 最近续期时刻（data 回报的 renewedAt，看门狗按此 + ttl + 宽限判活） */
    @Column(name = "last_renew_time", nullable = false)
    private OffsetDateTime lastRenewTime;

    /** 续种时延迟队列深度（>0 = 守卫跳过续种的轮次） */
    @Column(nullable = false)
    private int depth;

    /** 本轮实际应用的种子 TTL（毫秒） */
    @Column(name = "applied_ttl_ms", nullable = false)
    private long appliedTtlMs;

    @UpdateTimestamp
    @Column(name = "updated_at", columnDefinition = "timestamptz DEFAULT now() NOT NULL")
    private OffsetDateTime updatedAt;
}
