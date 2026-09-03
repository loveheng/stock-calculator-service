package com.zzh.stock_calculator.copilot.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * Copilot 消息实体。每条消息独立携带轻量概览与时间锚点，支持无原始快照的回放。
 * 级联软删除：父级 session 软删时同步标记 deleted_at。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "ai_chat_message", indexes = {
        @Index(name = "idx_ai_chat_message_session_id", columnList = "session_id, id DESC"),
        @Index(name = "idx_ai_chat_message_cid", columnList = "client_message_id")
}, uniqueConstraints = {
        @UniqueConstraint(name = "uq_ai_chat_message_client_id",
                columnNames = {"client_message_id"})
})
public class AiChatMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    @Column(nullable = false, length = 10)
    private String role;          // 'user' | 'assistant'

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "client_message_id", length = 40)
    private String clientMessageId;

    @Column(length = 20)
    private String status;        // 'ok' | 'failed' | 'pending'

    /** 极简指标 JSON（标量，无明细数组），不落库超过 255 字符 */
    @Column(name = "context_overview", length = 255)
    private String contextOverview;

    /** 时间截面标记 JSON（{"asOf":epochSec,"range":"7d"} 等） */
    @Column(name = "time_anchor", length = 100)
    private String timeAnchor;

    @Column(length = 30)
    private String channel;       // 'deepseek'

    @Column(length = 50)
    private String model;

    @Column(name = "prompt_tokens")
    private Integer promptTokens;

    @Column(name = "completion_tokens")
    private Integer completionTokens;

    @Column(nullable = false)
    private Long ctime;

    @Column(name = "deleted_at")
    private Long deletedAt;
}
