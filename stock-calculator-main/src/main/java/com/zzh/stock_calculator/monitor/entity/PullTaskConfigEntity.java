package com.zzh.stock_calculator.monitor.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.OffsetDateTime;

/**
 * 常态拉取任务配置（docs/pull-loop-unification-design.md §3/§8）：每任务一行，
 * main 控制面的调速/停启/调度事实源；LOOP 行配置经看门狗快照下发 data（D2，data 零 DB），
 * CALENDAR 行配置不下发（L10，data 对日历任务零配置依赖）。
 * task_code 手动维护（data.sql 播种）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "pull_task_config")
public class PullTaskConfigEntity {

    /** 调度模式（§8）：TTL 自循环 / 日历 cron（看门狗认领直发，无种子无 delay 队列） */
    public static final String MODE_LOOP = "LOOP";
    public static final String MODE_CALENDAR = "CALENDAR";

    /** 拉取源标识（外部 ID，手动写入；同时是 work routing key） */
    @Id
    @Column(name = "task_code", length = 64, nullable = false)
    private String taskCode;

    /** 续期开关（false = data 跳过续种循环死亡 + 看门狗停补种，L6；CALENDAR 行 = 停认领） */
    @Column(nullable = false)
    private boolean enabled;

    /** 种子 TTL（毫秒，per-message expiration；动态调速改此值，≤一个周期生效；CALENDAR 行哨兵 0） */
    @Column(name = "ttl_ms", nullable = false)
    private long ttlMs;

    /** 调度模式（schedule_mode，L8）：LOOP / CALENDAR */
    @Column(name = "schedule_mode", nullable = false)
    private String scheduleMode;

    /** cron 表达式（Spring CronExpression 六域方言，仅 CALENDAR 行；写入/启动双校验 fail-fast，L13） */
    @Column(name = "cron_expression", length = 64)
    private String cronExpression;

    /** cron 求值时区（仅 CALENDAR 行，显式钉死不依赖服务器默认值） */
    @Column(length = 64, nullable = false)
    private String timezone;

    /** 调度游标（仅 CALENDAR 行）：看门狗认领即推进（CAS 先于投递，L12）；NULL = 待初始化 */
    @Column(name = "next_expected_time")
    private OffsetDateTime nextExpectedTime;

    @UpdateTimestamp
    @Column(name = "updated_at", columnDefinition = "timestamptz DEFAULT now() NOT NULL")
    private OffsetDateTime updatedAt;
}
