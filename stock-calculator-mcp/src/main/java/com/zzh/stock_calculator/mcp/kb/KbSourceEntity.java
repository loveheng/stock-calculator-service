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
 * 订阅源注册表（stock_mcp.kb_source，mcp-blogger-kb M1）：博主观点库的源登记。
 * text = 本地 txt 文件路径（微博备份导出或普通文本）；rss = feed URL（M1b 实现拉取）。
 * 移除语义 = 停更保数据：status=removed 后轮询跳过、检索侧过滤，已入库观点保留。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "kb_source", uniqueConstraints = {
        @UniqueConstraint(name = "uq_kb_source_name", columnNames = {"name"})
})
public class KbSourceEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 128)
    private String name;

    @Column(name = "source_type", nullable = false, length = 16)
    private String sourceType;

    @Column(length = 512)
    private String location;

    @Column(nullable = false, length = 16)
    @Builder.Default
    private String status = "active";

    @Column(name = "last_ingested_at")
    private LocalDateTime lastIngestedAt;

    @CreationTimestamp
    @Column(name = "created_at", columnDefinition = "TIMESTAMP DEFAULT CURRENT_TIMESTAMP", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", columnDefinition = "TIMESTAMP DEFAULT CURRENT_TIMESTAMP")
    private LocalDateTime updatedAt;
}
