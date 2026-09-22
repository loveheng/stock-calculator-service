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

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "plan_id", nullable = false)
    private Long planId;

    @Column(name = "plan_dag_snapshot", nullable = false, columnDefinition = "JSONB")
    private JsonNode planDagSnapshot;

    /** 全链路追踪键（D9），随 MQ 消息头/REST 调用头下发 */
    @Column(name = "trace_id", nullable = false, length = 64)
    private String traceId;

    @Column(name = "user_id", nullable = false, length = 64)
    private String userId;

    /** 本次填充的具体参数（命中复用路径时 LLM 填槽 + 硬校验后的值） */
    @Column(nullable = false, columnDefinition = "JSONB")
    @Builder.Default
    private JsonNode params = new tools.jackson.databind.ObjectMapper().createObjectNode();

    /** 各节点状态（pending/running/done/failed + 按 output_policy 瘦身的输出摘要） */
    @Column(name = "node_states", nullable = false, columnDefinition = "JSONB")
    @Builder.Default
    private JsonNode nodeStates = new tools.jackson.databind.ObjectMapper().createObjectNode();

    /** running / done / failed / cancelled */
    @Column(nullable = false, length = 16)
    @Builder.Default
    private String status = "running";

    @Column(name = "created_at", columnDefinition = "TIMESTAMP DEFAULT CURRENT_TIMESTAMP", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", columnDefinition = "TIMESTAMP DEFAULT CURRENT_TIMESTAMP")
    private LocalDateTime updatedAt;
}
