package com.zzh.stock_calculator.orchestration.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import tools.jackson.databind.JsonNode;

import java.time.LocalDateTime;

/**
 * 匹配日志（agent-orchestration §6.2 阈值陷阱缓解②，P1-3）：每次向量复用匹配落一条——
 * query 向量 → top-k 命中明细 → 是否采纳/回退原因，供 pgvector 阈值调参回溯。
 * <p>append-only 不清理；query_vector vector(1024) 列不映射实体（PlanEntity 同款惯例），
 * 写入走 MatchLogRepository 原生 INSERT 显式 CAST。仅诊断面，业务零依赖（写失败不影响规划）。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "match_log")
public class MatchLogEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 本次查询的规范化意图（模板化锚文本，P4① 后= intent_template） */
    @Column(name = "query_text", nullable = false, columnDefinition = "TEXT")
    private String queryText;

    /** top-k 命中明细：[{plan_id, distance, adopted}] */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "top_hits", nullable = false, columnDefinition = "JSONB")
    @Builder.Default
    private JsonNode topHits = new tools.jackson.databind.ObjectMapper().createArrayNode();

    /** 是否采纳复用（命中且语义校验/填槽全过=TRUE） */
    @Column(nullable = false)
    @Builder.Default
    private Boolean adopted = false;

    /** 回退原因：no_hit / distance / needs_review / semantic_check / fill_failed /
     *  rejected_near（P2 负样本规避）/ embedding_unavailable */
    @Column(name = "fallback_reason", length = 32)
    private String fallbackReason;

    @Column(name = "created_at", columnDefinition = "TIMESTAMP DEFAULT CURRENT_TIMESTAMP", updatable = false)
    private LocalDateTime createdAt;
}
