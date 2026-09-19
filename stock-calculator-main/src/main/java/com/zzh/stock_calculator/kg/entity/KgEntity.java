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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * kg_entity 实体表（docs/ai-pipeline/cls-news-kg.md §5/D8）：字典锚点实体
 * （STOCK/CLS_SUBJECT，uq_kg_entity_anchor 定位）与自由实体（uq_kg_entity_type_name 定位）。
 * status 一期恒 ACTIVE；MERGED + canonical_id 为二期别名归并预留（无流转逻辑）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "kg_entity")
public class KgEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 规范名（去重锚点之一） */
    @Column(nullable = false)
    private String name;

    /** 实体类型：STOCK/SUBJECT/ORG/PERSON/PLACE/POLICY/EVENT/OTHER */
    @Column(name = "entity_type", nullable = false, length = 30)
    private String entityType;

    /** 锚点类型：STOCK/CLS_SUBJECT/null（自由实体），常量见 ClsDictAnchorApi */
    @Column(name = "anchor_type", length = 20)
    private String anchorType;

    /** 锚点业务键：stock_id / subject_id（字符串统一承载） */
    @Column(name = "anchor_id", length = 64)
    private String anchorId;

    /** 别名表（全称/简称/英文缩写/上市主体名，供后续锚点匹配与检索） */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<String> aliases;

    /** 首次见到该实体的文章发布时间 */
    @Column(name = "first_seen_at")
    private OffsetDateTime firstSeenAt;

    /** 最近一次提及时间（取 max，防乱序处理回退） */
    @Column(name = "last_seen_at")
    private OffsetDateTime lastSeenAt;

    /** 累计提及次数（每篇证据 +1） */
    @Column(name = "mention_count", nullable = false)
    @Builder.Default
    private Integer mentionCount = 0;

    /** 一期恒 ACTIVE；二期别名归并引入 MERGED */
    @Column(nullable = false, length = 10)
    @Builder.Default
    private String status = "ACTIVE";

    /** MERGED 时指向规范实体（二期启用） */
    @Column(name = "canonical_id")
    private Long canonicalId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
