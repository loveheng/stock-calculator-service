package com.zzh.stock_calculator.mcp.kb;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * 书块（stock_mcp.kb_chunk）。embedding vector(1024) 列不映射进实体（Hibernate 无原生
 * vector 类型）：写入走 @Modifying 原生 UPDATE CAST(:qv AS vector)，检索走原生 cosine 查询。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "kb_chunk", indexes = {
        @Index(name = "idx_kb_chunk_book", columnList = "book_id, chunk_index")
})
public class KbChunkEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "book_id", nullable = false)
    private Long bookId;

    @Column(name = "chapter_path", length = 512)
    private String chapterPath;

    @Column(name = "chunk_index", nullable = false)
    private Integer chunkIndex;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "content_hash", length = 64)
    private String contentHash;

    /** 条目发布时间（微博备份条目提取；传统书/600-80 切块路径为 NULL），支撑观点时效排序 */
    @Column(name = "published_at")
    private LocalDateTime publishedAt;

    @Column(nullable = false, length = 64)
    @Builder.Default
    private String model = "@cf/baai/bge-m3";

    @CreationTimestamp
    @Column(name = "created_at", columnDefinition = "TIMESTAMP DEFAULT CURRENT_TIMESTAMP", updatable = false)
    private LocalDateTime createdAt;
}
