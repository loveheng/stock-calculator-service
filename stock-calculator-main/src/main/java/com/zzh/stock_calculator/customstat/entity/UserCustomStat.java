package com.zzh.stock_calculator.customstat.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
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
 * 自定义统计定义服务端持久化表（D17 契约：docs/custom-stats-server-sync.md）。
 *
 * @description 明文 JSONB 直存（非用户核心数据，不进 /api/sync E2EE 快照通道，无需 MEK）。
 *              payload 为完整定义 DTO 的 JSON 文本：服务端是哑存储，原样存取，
 *              绝不用自身时钟改写 payload 内任何业务时间戳（否则客户端 LWW 对账失真）。
 *              写入仅经 Repository 的 native upsert（JDBC String→jsonb 需显式 CAST），JPA 侧只读。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "user_custom_stat")
public class UserCustomStat {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 外部ID（authUserId，UUID 文本）；D17 文档写 BIGINT 系前端笔误，按 user_sync_data 先例（E1）用 varchar(64)+String */
    @Column(name = "user_id", nullable = false, length = 64)
    private String userId;

    /** 客户端生成的定义 id（uuid 形态字符串），(user_id, def_id) 唯一 */
    @Column(name = "def_id", nullable = false, length = 64)
    private String defId;

    /** 定义 DTO JSON（payload JSONB），原样存取不碰内容 */
    @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    private String payload;

    /** 客户端 updatedAt ISO 串：LWW 排序键，原样存储、原样回传（服务端不参与仲裁） */
    @Column(name = "updated_at_client", nullable = false, length = 40)
    private String updatedAtClient;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;
}
