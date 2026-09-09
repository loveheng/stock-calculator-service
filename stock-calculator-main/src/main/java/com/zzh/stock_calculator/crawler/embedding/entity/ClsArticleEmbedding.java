package com.zzh.stock_calculator.crawler.embedding.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;

/**
 * cls_article_embedding 状态表实体（设计文档 §3.2/§3.3）。
 * 主键为外部 ID（article_id 手动写入，无 @GeneratedValue）；
 * 无 @ManyToOne，外键用 Long 平铺，与现库惯例一致（无物理外键）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "cls_article_embedding", indexes = {
        @Index(name = "idx_cae_status", columnList = "status")
})
public class ClsArticleEmbedding {

    /** 关联 cls_article.id，外部 ID 手动写入 */
    @Id
    @Column(name = "article_id", nullable = false)
    private Long articleId;

    /** 两态：PENDING=待处理/失败待重试，DONE=已生成 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    @Builder.Default
    private EmbeddingStatus status = EmbeddingStatus.PENDING;

    /** 留档当前向量由哪个模型生成，支撑将来换模型时的全量重嵌（R9） */
    @Column(nullable = false, length = 64)
    @Builder.Default
    private String model = "@cf/baai/bge-m3";

    /** 输入文本 sha256 hex（防御性支撑正文变更重嵌） */
    @Column(name = "content_hash", length = 64)
    private String contentHash;

    /** 最近一次失败摘要（截断至 500 字符，日志红线：不含正文） */
    @Column(length = 500)
    private String error;

    /** 永久性失败连续次数（仅 PERMANENT 计次；成功清零；达 max-fail-attempts 落 FAILED） */
    @Column(name = "fail_count", nullable = false)
    @Builder.Default
    private Integer failCount = 0;

    /** 向量生成成功时间 */
    @Column(name = "embedded_at")
    private OffsetDateTime embeddedAt;

    @CreationTimestamp
    @Column(name = "created_at", columnDefinition = "TIMESTAMP DEFAULT CURRENT_TIMESTAMP", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", columnDefinition = "TIMESTAMP DEFAULT CURRENT_TIMESTAMP")
    private LocalDateTime updatedAt;
}
