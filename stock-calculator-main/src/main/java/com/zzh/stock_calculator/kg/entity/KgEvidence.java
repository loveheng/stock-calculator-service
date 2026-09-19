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
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;

/**
 * kg_evidence 证据行实体（docs/ai-pipeline/cls-news-kg.md §5/D7）：worker 原始抽取 JSON
 * 的落库事实源——图谱可随时从证据重建（抽取噪声自愈），一篇文章一版（UNIQUE article_id，
 * 改稿重抽覆盖旧版）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "kg_evidence")
public class KgEvidence {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 关联 cls_article.id（幂等锚点，唯一） */
    @Column(name = "article_id", nullable = false)
    private Long articleId;

    /** 抽取时正文 sha256 hex（与 cls_article_kg.content_hash 对账） */
    @Column(name = "content_hash", nullable = false, length = 64)
    private String contentHash;

    /** worker 原始抽取 JSON（KgExtraction 结构；重建图谱的唯一输入） */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String payload;

    /** 抽取模型标识（换模型重放证据的对照依据） */
    @Column(length = 100)
    private String model;

    @Column(name = "extracted_at", nullable = false)
    @Builder.Default
    private OffsetDateTime extractedAt = OffsetDateTime.now();

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
}
