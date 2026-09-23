package com.zzh.stock_calculator.orchestration.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import tools.jackson.databind.JsonNode;

import java.time.LocalDateTime;

/**
 * 领域事件收件箱（orchestration 事件先于挂起到达兜底）：fan-in 监听器收到无匹配挂起
 * 实例的 event.* 消息时落此表缓冲；实例挂起（mq_wait）落定时立即重放匹配事件并删除。
 * <p>容量护栏：同 event_type 超 1000 条丢弃新事件（防无界堆积）；过期行由
 * OrchestrationRetentionTask 滚动清理（默认 7 天）。写失败只告警不影响主流程
 * （超时兜底 MqWaitTimeoutScanner 不受影响）。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "domain_event_inbox",
        indexes = @Index(name = "idx_domain_event_inbox_type", columnList = "event_type"))
public class DomainEventInboxEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 完整 routing key（event.<name>[.<后缀>]），重放时按 mq_wait 声明事件名做同款前缀匹配 */
    @Column(name = "event_type", nullable = false, length = 128)
    private String eventType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "JSONB")
    @Builder.Default
    private JsonNode payload = new tools.jackson.databind.ObjectMapper().createObjectNode();

    @CreationTimestamp
    @Column(name = "created_at", columnDefinition = "TIMESTAMP DEFAULT CURRENT_TIMESTAMP", updatable = false)
    private LocalDateTime createdAt;
}
