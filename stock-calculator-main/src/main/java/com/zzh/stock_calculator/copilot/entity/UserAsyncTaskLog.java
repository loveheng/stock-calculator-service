package com.zzh.stock_calculator.copilot.entity;

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
import org.hibernate.annotations.UpdateTimestamp;

import java.time.OffsetDateTime;

/**
 * 异步任务通道映射审计表（步 6-1，实现文档 §5.1 定案）：main 侧 correlation_id ↔
 * orchestration task_instance.trace_id 映射持久化。MQ payload 只带 correlation_id
 * （脱敏口径），完成/失败事件回来后经本表还原 user_id 推送 SSE；同时是 SSE 断连
 * 重连恢复依据与异步任务调用审计。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "user_async_task_log", indexes = {
        @Index(name = "idx_user_async_task_user", columnList = "user_id, created_at DESC"),
        @Index(name = "idx_user_async_task_status", columnList = "status")
}, uniqueConstraints = {
        @UniqueConstraint(name = "uq_user_async_task_correlation", columnNames = "correlation_id"),
        @UniqueConstraint(name = "uq_user_async_task_task", columnNames = "task_id")
})
public class UserAsyncTaskLog {

    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_DONE = "DONE";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_TIMEOUT = "TIMEOUT";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** main 生成（UUID），MQ payload 与编排器只见此 ID */
    @Column(name = "correlation_id", nullable = false, length = 64)
    private String correlationId;

    /** 会话/通道标识（SSE 重连恢复定位） */
    @Column(name = "channel_id", length = 64)
    private String channelId;

    /** orchestration task_instance.trace_id */
    @Column(name = "task_id", nullable = false, length = 64)
    private String taskId;

    /** 仅 main 侧持有，永不下发编排器（脱敏口径） */
    @Column(name = "user_id", nullable = false, length = 64)
    private String userId;

    /** 任务类型（如 announcement_subscribe） */
    @Column(name = "task_type", nullable = false, length = 64)
    private String taskType;

    /** RUNNING / DONE / FAILED / TIMEOUT */
    @Column(nullable = false, length = 20)
    @Builder.Default
    private String status = STATUS_RUNNING;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
