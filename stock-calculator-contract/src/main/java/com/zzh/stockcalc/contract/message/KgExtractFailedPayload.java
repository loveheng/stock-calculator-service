package com.zzh.stockcalc.contract.message;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * result.kg.failed 的 payload（docs/ai-pipeline/cls-news-kg.md §4/D10）：
 * worker → main 的失败回报，语义仿 AnnouncementFailedPayload——worker 只上报
 * 分类与原因，状态机留主服务（fail_count 计次/终态判定/熔断窗口）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KgExtractFailedPayload {

    /** 错误三分类：TRANSIENT 进对账重发环 / PERMANENT 终态 / RATE_LIMITED 冷却 */
    public static final String ERROR_KIND_TRANSIENT = "TRANSIENT";
    public static final String ERROR_KIND_PERMANENT = "PERMANENT";
    public static final String ERROR_KIND_RATE_LIMITED = "RATE_LIMITED";

    /** cls_article 主键（幂等锚点） */
    private Long articleId;

    /** 抽取时正文指纹 */
    private String contentHash;

    /** 失败原因摘要（解析失败带 rawTail 尾段，见 lessons「LLM 网关」） */
    private String failReason;

    /** 错误三分类：ERROR_KIND_* 常量 */
    private String errorKind;

    /** 原始异常摘要（排查可读性） */
    private String message;
}
