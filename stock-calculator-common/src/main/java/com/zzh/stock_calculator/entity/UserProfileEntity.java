package com.zzh.stock_calculator.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * E2EE 密文档案（docs/e2ee-auth-backend-design.md §D.3.2），列名与前端 user_profiles 完全一致。
 *
 * @description 服务端只存密文四元组、不解其语义（零知识红线）；
 *              updatedAt 由服务端维护，每次更新自动刷新，兼作 If-Match 版本号（决策 B5）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "user_profiles")
public class UserProfileEntity {

    /** 主键 = 用户 id（1:1）；FK 由 DDL 维护（user_profiles_id_fkey），Java 侧平铺不建关联 */
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** KEK 封装 MEK 的密文（客户端产物） */
    @Column(name = "password_payload", nullable = false, columnDefinition = "text")
    private String passwordPayload;

    /** 12 字节 IV 的 Base64（16 字符） */
    @Column(name = "password_iv", nullable = false, length = 32)
    private String passwordIv;

    /** Recovery Key 封装 MEK 的密文（客户端产物） */
    @Column(name = "recovery_payload", nullable = false, columnDefinition = "text")
    private String recoveryPayload;

    @Column(name = "recovery_iv", nullable = false, length = 32)
    private String recoveryIv;

    /** 服务端维护；@UpdateTimestamp 在 insert/update 时自动刷新 */
    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false, columnDefinition = "timestamptz")
    private OffsetDateTime updatedAt;

    @CreationTimestamp
    @Column(name = "created_at", columnDefinition = "timestamptz", updatable = false)
    private OffsetDateTime createdAt;
}
