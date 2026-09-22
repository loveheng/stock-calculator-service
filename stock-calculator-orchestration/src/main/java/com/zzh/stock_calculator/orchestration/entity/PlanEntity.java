package com.zzh.stock_calculator.orchestration.entity;

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
import tools.jackson.databind.JsonNode;

import java.time.LocalDateTime;

/**
 * 规划路径（stock_mcp.plan，agent-orchestration §6.2）。intent_embedding vector(1024) 列
 * 不映射进实体（mcp KbChunkEntity 同款惯例）：写入走 @Modifying 原生 UPDATE CAST，
 * 检索走 PlanRepository 原生 Filtered Vector Search。
 * <p>status 流水线（D10）：draft → candidate（自动冒烟通过）→ verified（HITL 人工确认）
 * / deprecated。needs_review=TRUE 时跳过复用直接重规划（§八 惰性回归）。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "plan", indexes = {
        @Index(name = "idx_plan_status", columnList = "status")
})
public class PlanEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 规范化意图（LLM 提炼：做什么+触发条件+交付物），非用户原话（D3 复用锚） */
    @Column(name = "intent_text", nullable = false, columnDefinition = "TEXT")
    private String intentText;

    /** 领域标签（可多值），向量检索的前置标量过滤器（§七） */
    @Column(name = "intent_domains", nullable = false, columnDefinition = "TEXT[]")
    @Builder.Default
    private String[] intentDomains = new String[0];

    @Column(name = "param_schema", nullable = false, columnDefinition = "JSONB")
    @Builder.Default
    private JsonNode paramSchema = new tools.jackson.databind.ObjectMapper().createObjectNode();

    /** DAG：节点（tool/input_mapping/depends_on/retry/timeout/成功判据），取值 $ctx 寻址（§八） */
    @Column(name = "plan_dag", nullable = false, columnDefinition = "JSONB")
    private JsonNode planDag;

    /** draft / candidate / verified / deprecated */
    @Column(nullable = false, length = 16)
    @Builder.Default
    private String status = "draft";

    /** 「待复核」标记（registry 变更惰性回归，§八）：命中即强制重规划不复用 */
    @Column(name = "needs_review", nullable = false)
    @Builder.Default
    private Boolean needsReview = false;

    @Column(name = "use_count", nullable = false)
    @Builder.Default
    private Long useCount = 0L;

    @Column(name = "last_used_at")
    private LocalDateTime lastUsedAt;

    @Column(name = "created_at", columnDefinition = "TIMESTAMP DEFAULT CURRENT_TIMESTAMP", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", columnDefinition = "TIMESTAMP DEFAULT CURRENT_TIMESTAMP")
    private LocalDateTime updatedAt;
}
