package com.zzh.stock_calculator.mcp.vision;

/**
 * OCR 渠道异常（责任链流转依据）：retryable=true（429/5xx/超时）由调度器重试后流转，
 * retryable=false（401/403 等鉴权/参数类）直接流转下一渠道。
 */
public class OcrChannelException extends RuntimeException {

    private final boolean retryable;

    public OcrChannelException(String message, boolean retryable) {
        super(message);
        this.retryable = retryable;
    }

    public OcrChannelException(String message, boolean retryable, Throwable cause) {
        super(message, cause);
        this.retryable = retryable;
    }

    public boolean isRetryable() {
        return retryable;
    }
}
