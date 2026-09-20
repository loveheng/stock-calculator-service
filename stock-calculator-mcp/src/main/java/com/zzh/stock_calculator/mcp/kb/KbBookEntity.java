package com.zzh.stock_calculator.mcp.kb;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * 书目元数据（stock_mcp.kb_book）：检索出处的拼接源。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "kb_book", uniqueConstraints = {
        @UniqueConstraint(name = "uq_kb_book_title", columnNames = {"title"})
})
public class KbBookEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 256)
    private String title;

    @Column(length = 128)
    private String author;

    @Column(length = 64)
    private String category;

    @Column(length = 16)
    private String difficulty;

    @Column(name = "reading_order")
    private Integer readingOrder;

    /** 关联订阅源 id（博主伪书；传统灌书为 NULL）——kb_search 按源移除状态过滤 */
    @Column(name = "source_id")
    private Long sourceId;

    @Column(name = "embedding_model", nullable = false, length = 64)
    @Builder.Default
    private String embeddingModel = "@cf/baai/bge-m3";

    @CreationTimestamp
    @Column(name = "created_at", columnDefinition = "TIMESTAMP DEFAULT CURRENT_TIMESTAMP", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", columnDefinition = "TIMESTAMP DEFAULT CURRENT_TIMESTAMP")
    private LocalDateTime updatedAt;
}
