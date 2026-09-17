package com.zzh.stock_calculator.copilot.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Copilot 长期记忆条目（docs/copilot/memory-profile.md §四）。
 * 条目归属会话窗口，(session_id, topic) 窗口内唯一=提炼即改写；跨窗口不去重（窗口即用户自分类主题）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "copilot_memory", uniqueConstraints = {
        @UniqueConstraint(name = "uq_copilot_memory_session_topic",
                columnNames = {"session_id", "topic"})
})
public class CopilotMemory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 对齐 ai_chat_session.user_id 口径（auth 用户 UUID 文本） */
    @Column(name = "user_id", nullable = false, length = 64)
    private String userId;

    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    /** 主题键：固定枚举池取值（防 LLM 同义变体绕过唯一约束），非池值入库时归一「杂项」（决策 #9） */
    @Column(nullable = false, length = 64)
    private String topic;

    /** 蒸馏后的记忆结论（同维度多结论合并陈述，≤300 字） */
    @Column(nullable = false, length = 2000)
    private String content;

    /** 溯源：来源 ai_chat_message.id 数组（仅用户提问）；入库侧保留最近 20 条防膨胀 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "source_message_ids", columnDefinition = "jsonb")
    private List<Long> sourceMessageIds;

    /** active / archived（遗忘软删） */
    @Column(length = 10)
    @Builder.Default
    private String status = "active";

    /** 置顶记忆：注入时全量携带（§七），上限 10 条 */
    @Column(nullable = false)
    @Builder.Default
    private Boolean pinned = false;

    /** 原始时间戳（秒），排序用 */
    @Column(nullable = false)
    private Long ctime;

    @CreationTimestamp
    @Column(name = "created_at", columnDefinition = "timestamptz DEFAULT CURRENT_TIMESTAMP", updatable = false)
    private OffsetDateTime createdAt;

    /** 画像 ΔCount 统计与变化驱动触发的依据（任何改写都会刷新） */
    @UpdateTimestamp
    @Column(name = "updated_at", columnDefinition = "timestamptz DEFAULT CURRENT_TIMESTAMP")
    private OffsetDateTime updatedAt;
}
