package com.zzh.stock_calculator.sync.entity;

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

/**
 * 服务端密文同步主表：每用户一行，最新密文快照（docs/server-sync-backend-design.md §3）。
 *
 * @description 零知识哑存储：只存信封密文，永不解析/解密。version 服务端单调自增（D5），
 *              仅经 Repository.casUpsert（native CAS）写入，JPA 侧只读；
 *              updated_at 是频控时钟（D10），写入路径全部由服务端产生。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "user_sync_data")
public class UserSyncData {

    /** 外部ID（authUserId，UUID 文本），手动写入（E1：String 非 Long，对齐 copilot 先例） */
    @Id
    @Column(name = "user_id", nullable = false, length = 64)
    private String userId;

    /** 密文信封 JSON（spec §4.1），原样存取不碰内容 */
    @Column(name = "encrypted_payload", nullable = false, columnDefinition = "TEXT")
    private String encryptedPayload;

    /** 服务端单调自增（D5）：INSERT 首传 1，覆盖 CAS 原子 +1；Java 侧永不手动改 */
    @Column(name = "version", nullable = false)
    private Long version;

    /** DB 可空、API 必填（E8）；仅用于去重行内比对，不建索引 */
    @Column(name = "payload_hash", length = 64)
    private String payloadHash;

    @Column(name = "payload_bytes", nullable = false)
    private Integer payloadBytes;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    /** 频控时钟（D10）；CAS 走 SQL NOW() 直改 DB，下次读实体即新值 */
    @UpdateTimestamp
    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;
}
