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

import java.time.OffsetDateTime;

/**
 * kg_event 事件表（docs/ai-pipeline/cls-news-kg.md §5/D11，一期时序主体）：
 * event_time 为归一化绝对时间（正文绝对日期优先，相对表述以文章 ctime 为基准），
 * event_time_text 保留原文表述可回溯；UNIQUE(article_id, content_hash) 事件级判重。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "kg_event")
public class KgEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 溯源：产生该事件的汇编文章 */
    @Column(name = "article_id", nullable = false)
    private Long articleId;

    /** 归一化事件时间（无法确定时为空，靠 event_time_text 兜底） */
    @Column(name = "event_time")
    private OffsetDateTime eventTime;

    /** 原文时间表述（time 为空时必填） */
    @Column(name = "event_time_text", length = 100)
    private String eventTimeText;

    /** 事件摘要句 */
    @Column(nullable = false)
    private String title;

    /** 补充细节（可空） */
    @Column(columnDefinition = "text")
    private String detail;

    /** 事件类型（一期不强制枚举） */
    @Column(name = "event_type", length = 30)
    private String eventType;

    /** 事件指纹 sha256(title|time|detail)，UNIQUE(article_id, content_hash) 判重 */
    @Column(name = "content_hash", nullable = false, length = 64)
    private String contentHash;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
}
