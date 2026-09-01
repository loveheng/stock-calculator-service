package com.zzh.stock_calculator.llm.service;

import lombok.Getter;

/**
 * 单个 LLM 渠道级异常：只表示「该渠道这次没成功」，
 * 由 {@link com.zzh.stock_calculator.llm.LlmChainRouter} 决定重试或流转下一渠道，
 * 不直接冒泡到 Controller（全部渠道失败时由调度器统一抛 BusinessException）。
 */
@Getter
public class LlmProviderException extends RuntimeException {

    /** true=瞬时故障（429/5xx/超时），可对同渠道重试；false=确定性失败（401/403），直接换渠道 */
    private final boolean retryable;

    public LlmProviderException(String message, boolean retryable) {
        super(message);
        this.retryable = retryable;
    }

    public LlmProviderException(String message, boolean retryable, Throwable cause) {
        super(message, cause);
        this.retryable = retryable;
    }
}
