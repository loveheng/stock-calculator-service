package com.zzh.stock_calculator.announcement.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * 公告主表（docs/announcement-rag-pipeline-design.md §3）：元数据 + 状态机游标，
 * 不存 PDF、不存正文（D5/D7）。唯一长期留存文本 = summary 蒸馏摘要。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "announcement",
        uniqueConstraints = @UniqueConstraint(name = "uk_announcement_announcement_id", columnNames = "announcement_id"),
        indexes = @Index(name = "idx_announcement_sec", columnList = "sec_code, se_date"))
public class Announcement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** CNINFO announcementId，全局幂等键（S3 实证） */
    @Column(name = "announcement_id", nullable = false, columnDefinition = "text")
    private String announcementId;

    /** 已清洗标题（剥 &lt;em&gt; 标记与 HTML 实体） */
    @Column(nullable = false, columnDefinition = "text")
    private String title;

    /** PDF 相对路径；下载地址 = http://static.cninfo.com.cn/ + adjunctUrl（S3 实证直出） */
    @Column(name = "adjunct_url", columnDefinition = "text")
    private String adjunctUrl;

    /** 公告日：announcementTime（epoch ms ≈ 北京 00:00）按 Asia/Shanghai 换算，回退 adjunctUrl 日期段 */
    @Column(name = "se_date")
    private LocalDate seDate;

    @Column(name = "sec_code", length = 32)
    private String secCode;

    @Column(name = "sec_name", length = 64)
    private String secName;

    /** 状态机：PENDING 待处理 / DONE 完成 / FAILED 终态（D9，状态即游标） */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    @Builder.Default
    private AnnouncementStatus status = AnnouncementStatus.PENDING;

    /** 细分失败/跳过原因（AnnouncementFailReason 枚举词典，防自由拼写） */
    @Enumerated(EnumType.STRING)
    @Column(name = "status_reason", columnDefinition = "text")
    private AnnouncementFailReason statusReason;

    /** 瞬时失败计次，达 process.max-fail-attempts 落 FAILED */
    @Builder.Default
    @Column(name = "fail_count", nullable = false)
    private Integer failCount = 0;

    /** 阶段二蒸馏的 200~300 字纯事实摘要（S5 接入） */
    @Column(columnDefinition = "text")
    private String summary;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, columnDefinition = "timestamptz DEFAULT now()")
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false, columnDefinition = "timestamptz DEFAULT now()")
    private OffsetDateTime updatedAt;
}
