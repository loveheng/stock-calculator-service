package com.zzh.stockcalc.contract.message;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * result.announcement.failed 的 payload（设计文档 §4.3/D7）：
 * worker → 主服务的失败回报，状态机留主服务——worker 只按名上报 failReason
 * （主服务 AnnouncementFailReason 枚举名，契约侧不引主服务类型）与 errorKind 三分类，
 * 主服务据此 failCount 计次/终态判定/暂停发布窗口。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AnnouncementFailedPayload {

    /** 错误三分类（§6.1）：TRANSIENT 进重试环 / PERMANENT 终态 / RATE_LIMITED 冷却 */
    public static final String ERROR_KIND_TRANSIENT = "TRANSIENT";
    public static final String ERROR_KIND_PERMANENT = "PERMANENT";
    public static final String ERROR_KIND_RATE_LIMITED = "RATE_LIMITED";

    /** CNINFO 唯一公告标识 */
    private String announcementId;

    /** 失败原因：主服务 AnnouncementFailReason 枚举名（DOWNLOAD_FAIL/PARSE_FAIL/...） */
    private String failReason;

    /** 错误三分类：ERROR_KIND_* 常量 */
    private String errorKind;

    /** 原始异常摘要（排查可读性） */
    private String message;
}
