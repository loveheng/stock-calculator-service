package com.zzh.stock_calculator.kg.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
 * cls_article_kg 状态表实体（docs/ai-pipeline/cls-news-kg.md §5；复刻 cls_article_embedding 范式）。
 * 主键为外部 ID（article_id 手动写入，无 @GeneratedValue）；无 @ManyToOne，外键 Long 平铺
 * （现库惯例，无物理外键）。部分索引 idx_cls_article_kg_pending 仅存于 DDL（partial index
 * JPA 注解表达不了），由 spring.sql.init 幂等创建。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "cls_article_kg")
public class ClsArticleKg {

    /** 关联 cls_article.id，外部 ID 手动写入（幂等锚点） */
    @Id
    @Column(name = "article_id", nullable = false)
    private Long articleId;

    /** PENDING=待处理/待重试（发布器游标），DONE=证据已落+融合完成，FAILED=人工终态 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    @Builder.Default
    private KgTaskStatus status = KgTaskStatus.PENDING;

    /** 失败原因/融合失败标记（截断至 200 字符，日志红线：不含正文） */
    @Column(name = "status_reason", length = 200)
    private String statusReason;

    /** 瞬时失败连续次数（仅 worker 回报计次；达 max-fail-attempts 落 FAILED） */
    @Column(name = "fail_count", nullable = false)
    @Builder.Default
    private Integer failCount = 0;

    /** 下发/摄取时正文 sha256 hex（源站改稿检测：hash 变化触发重抽） */
    @Column(name = "content_hash", nullable = false, length = 64)
    private String contentHash;

    /** 最近一次成功抽取时间 */
    @Column(name = "extracted_at")
    private OffsetDateTime extractedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
