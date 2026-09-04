package com.zzh.stock_calculator.sync.entity;

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

import java.time.OffsetDateTime;

/**
 * 服务端密文同步历史表：被替换版本，保留最近 5 份（D8，裁剪在 service 层）。
 *
 * @description 仅经 Repository.insertIgnore（native，ON CONFLICT DO NOTHING，E7）写入，
 *              唯一约束 (user_id, version) 兼作裁剪依据。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "user_sync_history",
       uniqueConstraints = @UniqueConstraint(name = "uq_user_sync_history",
               columnNames = {"user_id", "version"}))
public class UserSyncHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, length = 64)
    private String userId;

    /** 被替换时的云端版本号 */
    @Column(name = "version", nullable = false)
    private Long version;

    @Column(name = "encrypted_payload", nullable = false, columnDefinition = "TEXT")
    private String encryptedPayload;

    @Column(name = "payload_bytes", nullable = false)
    private Integer payloadBytes;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;
}
