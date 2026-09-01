package com.zzh.stock_calculator.vision.service;

import lombok.Getter;

/**
 * 单个 OCR 渠道级异常：只表示「该渠道这次没成功」，
 * 由 {@link OcrChainManager} 决定重试或流转下一渠道，不直接冒泡到 Controller
 * （全部渠道失败时由调度器统一抛 BusinessException）。
 */
@Getter
public class OcrChannelException extends RuntimeException {

    /** true=瞬时故障（429/5xx/超时），可对同渠道重试；false=确定性失败（401/403、任务 Failed），直接换渠道 */
    private final boolean retryable;

    public OcrChannelException(String message, boolean retryable) {
        super(message);
        this.retryable = retryable;
    }

    public OcrChannelException(String message, boolean retryable, Throwable cause) {
        super(message, cause);
        this.retryable = retryable;
    }
}
