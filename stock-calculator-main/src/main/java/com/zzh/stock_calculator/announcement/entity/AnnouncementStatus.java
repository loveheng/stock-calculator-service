package com.zzh.stock_calculator.announcement.entity;

/**
 * 公告处理状态机（设计文档 §3/D9）：三态即游标，复刻 cls_article_embedding 范式；
 * 细分原因走 status_reason，不加第四态。
 */
public enum AnnouncementStatus {

    /** 待处理（骨架期：解析产物已入库、蒸馏未接入的过渡态） */
    PENDING,

    /** 全链路完成：蒸馏摘要已落库 */
    DONE,

    /** 永久失败终态（maxFailAttempts 达限 / PERMANENT 错误 / 毒丸跳过），不再消耗队列 */
    FAILED
}
