package com.zzh.stock_calculator.dto;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "cls_article_subject", uniqueConstraints = {
        @UniqueConstraint(name = "uk_article_subject", columnNames = {"article_id", "subject_id"})
}, indexes = {
        @Index(name = "idx_csub_article_id", columnList = "article_id"),
        @Index(name = "idx_csub_subject_id", columnList = "subject_id")
})
public class ClsArticleSubject {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "article_id", nullable = false)
    private Long articleId;

    @Column(name = "subject_id", nullable = false)
    private Long subjectId;

    @CreationTimestamp
    @Column(name = "created_at", columnDefinition = "TIMESTAMP DEFAULT CURRENT_TIMESTAMP",
            updatable = false)
    private LocalDateTime createdAt;
}