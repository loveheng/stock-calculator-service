package com.zzh.stock_calculator.kg.entity;

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
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * kg_relation 关系边表（docs/ai-pipeline/cls-news-kg.md §5/D11）：一期落库但
 * valid_from/valid_to 不启用（事件时间线为主）；边级溯源 evidence_article_id 是
 * 重放/去重的锚（UNIQUE 含 evidence_article_id，同文章重复摄取零副作用）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "kg_relation")
public class KgRelation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 主体实体 id（kg_entity.id，无物理外键） */
    @Column(name = "subject_entity_id", nullable = false)
    private Long subjectEntityId;

    /** 客体实体 id */
    @Column(name = "object_entity_id", nullable = false)
    private Long objectEntityId;

    /** 受控谓词（出台/发布/召开/签署/合作/任命/增长/下降/投资/扩大/禁止/推进/其他） */
    @Column(nullable = false, length = 50)
    private String predicate;

    /** 二期启用：关系有效起点 */
    @Column(name = "valid_from")
    private OffsetDateTime validFrom;

    /** 二期启用：关系有效终点 */
    @Column(name = "valid_to")
    private OffsetDateTime validTo;

    /** 模型自评置信度 0~1 */
    @Column(precision = 4, scale = 3)
    private BigDecimal confidence;

    /** 溯源：该边由哪篇文章的证据产出 */
    @Column(name = "evidence_article_id", nullable = false)
    private Long evidenceArticleId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
}
