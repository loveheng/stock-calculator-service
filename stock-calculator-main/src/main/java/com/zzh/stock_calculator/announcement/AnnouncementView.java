package com.zzh.stock_calculator.announcement;

import java.time.LocalDate;

/**
 * 公告检索视图（基包公开 DTO）：跨域查询结果载体，避免向消费方暴露 entity 内部类型。
 * 字段为 search 检索/档案卡/回填任务所需最小集。
 *
 * @param id           内部自增主键（回填任务调 processAnnouncement 用）
 * @param announcementId CNINFO 公告标识（resultId 口径）
 * @param status       状态机文本（PENDING/DONE/FAILED）
 * @param sourceUrl    adjunctUrl 补全后的完整下载地址（CNINFO 静态前缀拼接，原 CninfoClient 规则）
 */
public record AnnouncementView(
        Long id,
        String announcementId,
        String title,
        String secCode,
        String secName,
        LocalDate seDate,
        String adjunctUrl,
        String summary,
        String status,
        String sourceUrl) {
}
