package com.zzh.stock_calculator.notify.entity;

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
 * PWA Web Push 订阅（docs/notify/design.md 触达扩展）。
 *
 * @description 保存浏览器 pushManager.subscribe 返回的订阅三元组：endpoint + p256dh + auth，
 *              发送时经 VAPID 签名 + RFC 8291 加密投递到推送服务（FCM/APNs Web 接口）。
 *              endpoint 全局唯一（同一浏览器重复订阅是同一 endpoint，upsert 覆盖即可）；
 *              推送服务返回 404/410 时物理删除（订阅已失效，浏览器不会复用）。
 * @author 开发团队
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "push_subscription")
public class PushSubscription {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 归属用户（authUserId，UUID 文本，与 user_sync_data 先例一致用 varchar(64)） */
    @Column(name = "user_id", nullable = false, length = 64)
    private String userId;

    /** 推送服务订阅端点 URL（https） */
    @Column(name = "endpoint", nullable = false, length = 1024)
    private String endpoint;

    /** 客户端 ECDH P-256 公钥（base64url），RFC 8291 加密用 */
    @Column(name = "p256dh", nullable = false, length = 200)
    private String p256dh;

    /** 认证密钥（base64url），RFC 8291 加密用 */
    @Column(name = "auth", nullable = false, length = 100)
    private String auth;

    /** 登记时的 UA 摘要（运维排查用，可选） */
    @Column(name = "user_agent", length = 300)
    private String userAgent;

    /** 最近一次投递成功时间（NULL = 从未成功）；僵尸订阅惰性清理依据 */
    @Column(name = "last_success_at")
    private OffsetDateTime lastSuccessAt;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;
}
