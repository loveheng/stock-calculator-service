package com.zzh.stock_calculator.dto;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "cls_article_stock", uniqueConstraints = {
        @UniqueConstraint(name = "uk_article_stock", columnNames = {"article_id", "stock_id"})
}, indexes = {
        @Index(name = "idx_cas_article_id", columnList = "article_id"),
        @Index(name = "idx_cas_stock_id", columnList = "stock_id")
})
public class ClsArticleStock {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "article_id", nullable = false)
    private Long articleId;

    @Column(name = "stock_id", nullable = false, length = 32)
    private String stockId;

    @Column(name = "last_price", precision = 12, scale = 3)
    private BigDecimal lastPrice;

    @Column(name = "rise_range", precision = 8, scale = 2)
    private BigDecimal riseRange;

    @CreationTimestamp
    @Column(name = "created_at", columnDefinition = "TIMESTAMP DEFAULT CURRENT_TIMESTAMP",
            updatable = false)
    private LocalDateTime createdAt;
}