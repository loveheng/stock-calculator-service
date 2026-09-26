package com.zzh.stock_calculator.mcp.vision;

/**
 * OCR 渠道策略接口（从 main vision 域平移，@Order 决定责任链优先级：azure → ocrspace）。
 * 渠道实现只负责「图进、文本出」；缓存/重试/降级统一在 {@link OcrChainManager}。
 */
public interface OcrService {

    /** 渠道名（日志与失败汇总用） */
    String channelName();

    /** 渠道健康检查：未启用或缺 Key 的渠道被调度器跳过 */
    boolean isAvailable();

    /**
     * 识别图片文字。
     *
     * @param imageBytes 图片字节（非空，空校验在调度器）
     * @param language   语言提示（如 "chs"）
     * @return 识别文本；图中无文字时返回 ""
     * @throws OcrChannelException 识别失败（retryable 决定调度器行为）
     */
    String recognizeText(byte[] imageBytes, String language);
}
