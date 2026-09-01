package com.zzh.stock_calculator.auth.entity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.OffsetDateTime;

/**
 * 找回验证码（docs/e2ee-auth-backend-design.md §D.3.4）。
 *
 * @description 6 位码哈希落库（SHA-256(email + ":" + code)），不存原文；
 *              10 分钟有效、单次消费、5 次尝试锁死（决策 B2 / §D.5.2）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "otp_codes", indexes = @Index(name = "idx_otp_codes_email", columnList = "email"))
public class OtpCodeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 归一化邮箱 */
    @Column(name = "email", nullable = false, length = 255)
    private String email;

    @Column(name = "code_hash", nullable = false, length = 64)
    private String codeHash;

    /** 预留多用途，本期固定 recovery */
    @Column(name = "purpose", nullable = false, length = 16)
    @Builder.Default
    private String purpose = "recovery";

    /** 校验失败计数，达到上限即作废 */
    @Column(name = "attempts", nullable = false)
    @Builder.Default
    private Integer attempts = 0;

    @Column(name = "expires_at", nullable = false, columnDefinition = "timestamptz")
    private OffsetDateTime expiresAt;

    /** 单次消费标记；置位后不可再用 */
    @Column(name = "consumed_at", columnDefinition = "timestamptz")
    private OffsetDateTime consumedAt;

    @CreationTimestamp
    @Column(name = "created_at", columnDefinition = "timestamptz", updatable = false)
    private OffsetDateTime createdAt;
}
