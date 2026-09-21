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

import java.time.OffsetDateTime;

/**
 * 推送消息落库（漏达补拉通道）。
 *
 * @description 与订阅解耦：无论用户是否有有效推送订阅，消息先落库——
 *              推送是「尽力触达」，打开 PWA 时拉未读是兜底，两通道共用同一条消息。
 *              read_at 为 NULL 即未读；点击通知/打开消息面板标记已读。
 * @author 开发团队
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "push_message")
public class PushMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 归属用户（authUserId，UUID 文本） */
    @Column(name = "user_id", nullable = false, length = 64)
    private String userId;

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @Column(name = "body", nullable = false, length = 1000)
    private String body;

    /** 点击通知后打开的页面路径（与推送 payload 的 url 同源） */
    @Column(name = "url", length = 500)
    private String url;

    /** 已读时间（NULL = 未读） */
    @Column(name = "read_at")
    private OffsetDateTime readAt;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;
}
