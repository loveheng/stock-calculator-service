package com.zzh.stock_calculator.crawler.entity;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "cls_article", indexes = {
        @Index(name = "idx_cls_article_ctime", columnList = "ctime DESC"),
        @Index(name = "idx_cls_article_level", columnList = "level")
})
public class ClsArticle {

    @Id
    @Column(nullable = false)
    private Long id;

    @Column(nullable = false, columnDefinition = "INT NOT NULL DEFAULT -1")
    private Integer type;

    @Column(length = 500)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String brief;

    @Column(columnDefinition = "TEXT")
    private String content;

    @Column(nullable = false)
    private Long ctime;

    @Column(name = "created_at", insertable = false, updatable = false,
            columnDefinition = "TIMESTAMPTZ GENERATED ALWAYS AS (to_timestamp(ctime)) STORED")
    private OffsetDateTime createdAt;

    @Column(length = 100)
    @Builder.Default
    private String author = "";

    @Column(length = 10)
    @Builder.Default
    private String level = "C";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "JSONB")
    private List<String> images;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "audio_url", columnDefinition = "JSONB")
    private List<String> audioUrl;

    @CreationTimestamp
    @Column(name = "fetched_at", columnDefinition = "TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP")
    private OffsetDateTime fetchedAt;
}
