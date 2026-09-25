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

import java.time.Instant;
import java.time.OffsetDateTime;

/**
 * 提醒登记表实体（stock_mcp 库 reminder 表，docs/notify/design.md §4.1）。
 * trigger_spec / action 为 JSONB：JPA 侧以 String 承载（jsonb ↔ text 赋值由
 * JdbcTypeCode 处理），结构校验在 Service 层做（不引表达式引擎，轻量 JSON 条件）。
 * 状态机单向 active → done / cancelled / failed（failed = trigger_spec/action JSON
 * 确定性损坏的显式失败态，终止调度防幽灵 active 永久漏触发；N6：修改走删除重建，无原地 update）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "reminder", indexes = {
        @Index(name = "idx_reminder_user_active", columnList = "user_id, status"),
        @Index(name = "idx_reminder_status_trigger", columnList = "status, trigger_type")
})
public class ReminderEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 归属用户（copilot 持会话身份传入） */
    @Column(name = "user_id", nullable = false, length = 64)
    private String userId;

    /** at_time / on_event */
    @Column(name = "trigger_type", nullable = false, length = 16)
    private String triggerType;

    /** at_time：{nextFireAt,repeat}；on_event：{eventType,filter}（JSONB 文本；
     *  SqlTypes.JSON 映射——缺省 String 直写会报 jsonb/varchar 类型不匹配） */
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    @Column(name = "trigger_spec", nullable = false, columnDefinition = "jsonb")
    private String triggerSpec;

    /** {kind:text|capability, payload}（JSONB 文本，映射同 trigger_spec） */
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    @Column(name = "action", nullable = false, columnDefinition = "jsonb")
    private String action;

    /** active / done / cancelled / failed（failed = JSON 确定性损坏，仅调度链内部置入） */
    @Column(name = "status", nullable = false, length = 16)
    @Builder.Default
    private String status = "active";

    /** 最小触发间隔（毫秒，N7 运行期限幅；登记时按类型赋默认值） */
    @Column(name = "min_interval_ms")
    private Long minIntervalMs;

    /** 动态退避冷静期（N7：窗口内洪峰自动拉长间隔，恢复后回落） */
    @Column(name = "suppress_until")
    private OffsetDateTime suppressUntil;

    /** 最近一次触发时间（幂等与观测） */
    @Column(name = "fired_at")
    private OffsetDateTime firedAt;

    /** 累计触发次数（reminder_list 观测可见） */
    @Column(name = "fire_count", nullable = false)
    @Builder.Default
    private Integer fireCount = 0;

    /** 累计被限幅命中次数（N7 观测：用户可发现「我的提醒被限流了」） */
    @Column(name = "suppressed_count", nullable = false)
    @Builder.Default
    private Integer suppressedCount = 0;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    /** 深拷贝（删除重建 N6：update 时按旧行字段重建新行） */
    public ReminderEntity copyForRecreate() {
        return ReminderEntity.builder()
                .userId(userId)
                .triggerType(triggerType)
                .triggerSpec(triggerSpec)
                .action(action)
                .status("active")
                .minIntervalMs(minIntervalMs)
                .build();
    }
}
