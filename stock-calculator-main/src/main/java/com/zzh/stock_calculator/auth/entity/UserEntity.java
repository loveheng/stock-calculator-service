package com.zzh.stock_calculator.auth.entity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * E2EE 用户账号（docs/e2ee-auth-backend-design.md §D.3.1）。
 *
 * @description 密码列为 bcrypt(10) over authHash（前端 PBKDF2-SHA256 派生的 64 位小写 hex）；
 *              authHash 明文不落任何存储，日志不得输出（零知识红线，决策 B2）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "users", uniqueConstraints = @UniqueConstraint(name = "users_email_key", columnNames = "email"))
public class UserEntity {

    /** 主键：注册时 Java 侧生成 UUID（对齐前端 user_profiles.id 引用型语义） */
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** 归一化邮箱（trim + 小写），唯一 */
    @Column(name = "email", nullable = false, length = 255)
    private String email;

    /** bcrypt(10) 摘要（60 字符），原文 authHash 不落库 */
    @Column(name = "password_hash", nullable = false, length = 60)
    private String passwordHash;

    @CreationTimestamp
    @Column(name = "created_at", columnDefinition = "timestamptz", updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", columnDefinition = "timestamptz")
    private OffsetDateTime updatedAt;
}
