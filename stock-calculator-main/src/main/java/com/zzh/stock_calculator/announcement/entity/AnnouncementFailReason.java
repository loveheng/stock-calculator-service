package com.zzh.stock_calculator.announcement.entity;

/**
 * status_reason 枚举词典（设计文档 §3）：完整枚举防不同开发随意拼写，
 * 保证后期按原因统计/报警的 SQL 不失效。
 */
public enum AnnouncementFailReason {

    /** PDF 下载失败（网络 404 / 超体积上限拒绝 / 内容非 PDF 反复重试仍失败） */
    DOWNLOAD_FAIL,

    /** 解析异常（PDFBox 抛出的非密锁类错误） */
    PARSE_FAIL,

    /** 解析超时（pdf.parse-timeout-seconds 超限，v0.2 接入） */
    PARSE_TIMEOUT,

    /** 加密/权限锁 PDF 且解密失败（§5 毒丸一） */
    SKIPPED_ENCRYPTED,

    /** 抽取文本过短（扫描件/空文本，§5 毒丸三） */
    SKIPPED_NO_TEXT,

    /** LLM 阶段一路由调用失败（S5 接入） */
    LLM_ROUTE_FAIL,

    /** 数值接地校验失败且定向重试仍失败（D8） */
    GROUNDING_FAIL
}
