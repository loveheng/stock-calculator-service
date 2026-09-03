package com.zzh.stock_calculator.copilot.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * Copilot 会话实体。仅存储元数据，不存完整快照。
 * 软删后同 (user_id, scope_id) 可复用创建新会话。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "ai_chat_session", uniqueConstraints = {
        @UniqueConstraint(name = "uq_ai_chat_session_user_scope",
                columnNames = {"user_id", "scope_id"})
})
public class AiChatSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, length = 64)
    private String userId;

    /** 页面级（statistics）或 页面:实体主键（cost_averaging:600519） */
    @Column(name = "scope_id", nullable = false, length = 100)
    private String scopeId;

    @Column(nullable = false, length = 100)
    private String title;

    @Column(name = "last_message_at")
    private Long lastMessageAt;

    @Column(nullable = false)
    private Long ctime;

    @Column(name = "deleted_at")
    private Long deletedAt;
}
