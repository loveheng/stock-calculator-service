package com.zzh.stock_calculator.copilot.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

/**
 * Copilot 用户画像 + 固化状态（docs/copilot/memory-profile.md §四）。
 * 一用户一行，懒创建 upsert（首次画像任务或手动重抽时创建）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "copilot_user_profile")
public class CopilotUserProfile {

    /** 业务主键 = 用户 ID（对齐 ai_chat_session.user_id 口径），手动写入 */
    @Id
    @Column(name = "user_id", nullable = false, length = 64)
    private String userId;

    /** 画像四字段（personality/deepPreferences/taboos/responsePreferences），禁 PII */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private ProfileFields profile;

    /** 每次全量重抽 +1（人工修正亦 +1，决策 #21） */
    @Column(name = "profile_version", nullable = false)
    @Builder.Default
    private Integer profileVersion = 0;

    /** 画像黑名单：用户手动移除的特征，后续抽取严禁再输出（决策 #21） */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "blacklisted_features", columnDefinition = "jsonb")
    @Builder.Default
    private List<String> blacklistedFeatures = List.of();

    /** 画像变化驱动游标：仅推进至任务快照 max(updated_at)，绝不用 now()（决策 #19） */
    @Column(name = "last_profile_extracted_at")
    private OffsetDateTime lastProfileExtractedAt;

    @CreationTimestamp
    @Column(
        name = "created_at",
        columnDefinition = "timestamptz DEFAULT CURRENT_TIMESTAMP",
        updatable = false
    )
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(
        name = "updated_at",
        columnDefinition = "timestamptz DEFAULT CURRENT_TIMESTAMP"
    )
    private OffsetDateTime updatedAt;

    /** 画像 JSON 结构（§四）：四类特征数组，证据不足为空数组（序列化形状由外层 jsonb 字段驱动） */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ProfileFields {

        private List<String> personality;

        private List<String> deepPreferences;

        private List<String> taboos;

        private List<String> responsePreferences;
    }
}
