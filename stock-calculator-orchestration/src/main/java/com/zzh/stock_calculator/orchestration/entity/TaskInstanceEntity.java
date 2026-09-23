package com.zzh.stock_calculator.orchestration.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import tools.jackson.databind.JsonNode;

import java.time.LocalDateTime;

/**
 * 任务执行实例（stock_mcp.task_instance，agent-orchestration §6.3）。
 * plan_dag_snapshot 创建时冗余（版本漂移防护：plan 后续变更不影响在跑实例，§八）；
 * node_states 按 registry 的 output_policy 瘦身落库（keep_summary/keep_head/keep_ref）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "task_instance",
        indexes = {
                @Index(name = "idx_task_instance_status", columnList = "status"),
                @Index(name = "idx_task_instance_plan", columnList = "plan_id")
        },
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_task_instance_trace", columnNames = "trace_id")
        })
public class TaskInstanceEntity {

    /** 实例状态机：running（执行中）/ waiting（mq_wait 挂起，等 MQ 事件唤醒）/ done / failed / timeout */
    public static final String ST_RUNNING = "running";
    public static final String ST_WAITING = "waiting";
    public static final String ST_DONE = "done";
    public static final String ST_FAILED = "failed";
    public static final String ST_TIMEOUT = "timeout";

    /** params.purpose=smoke：自动冒烟实例（P1-5）——Executor 只验结构不触外部系统，
     *  终态回调走 SmokeGateService 状态机而非普通终态事件 */
    public static final String PURPOSE_SMOKE = "smoke";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "plan_id", nullable = false)
    private Long planId;

    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    @Column(name = "plan_dag_snapshot", nullable = false, columnDefinition = "JSONB")
    private JsonNode planDagSnapshot;

    /** 全链路追踪键（D9），随 MQ 消息头/REST 调用头下发 */
    @Column(name = "trace_id", nullable = false, length = 64)
    private String traceId;

    @Column(name = "user_id", nullable = false, length = 64)
    private String userId;

    /** 本次填充的具体参数（命中复用路径时 LLM 填槽 + 硬校验后的值） */
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "JSONB")
    @Builder.Default
    private JsonNode params = new tools.jackson.databind.ObjectMapper().createObjectNode();

    /** 各节点状态（pending/running/done/failed + 按 output_policy 瘦身的输出摘要） */
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    @Column(name = "node_states", nullable = false, columnDefinition = "JSONB")
    @Builder.Default
    private JsonNode nodeStates = new tools.jackson.databind.ObjectMapper().createObjectNode();

    /** running / done / failed / cancelled / waiting（mq_wait 挂起） / timeout */
    @Column(nullable = false, length = 16)
    @Builder.Default
    private String status = "running";

    /** mq_wait 挂起截止时间（NULL=非挂起）；超时由 @Scheduled 扫描置 timeout（Zombie 防御） */
    @Column(name = "wait_deadline")
    private LocalDateTime waitDeadline;

    @Column(name = "created_at", columnDefinition = "TIMESTAMP DEFAULT CURRENT_TIMESTAMP", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", columnDefinition = "TIMESTAMP DEFAULT CURRENT_TIMESTAMP")
    private LocalDateTime updatedAt;

    /** 是否冒烟实例（params.purpose=smoke，P1-5）：终态走 plan 升格状态机，不计 use_count */
    public boolean isSmokeRun() {
        return params != null && PURPOSE_SMOKE.equals(params.path("purpose").asText(""));
    }
}
