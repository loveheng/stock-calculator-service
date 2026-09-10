package com.zzh.stock_calculator.common;

import lombok.Getter;

/**
 * 限流异常（backend-implementation §5.1）：code 固定 429，携带 retryAfterSeconds 供前端倒计时。
 * <p>与 {@link BusinessException} 分离的原因：429 信封需带 data.retryAfterSeconds
 * （api 文档 §1），而既有 BusinessException → ApiResponse.fail(code, message) 的 data 恒 null；
 * 为不破坏存量 429（copilot/auth 无 data）语义，新增独立异常类型 + 独立 handler。</p>
 */
@Getter
public class RateLimitedException extends RuntimeException {

    private final long retryAfterSeconds;

    public RateLimitedException(long retryAfterSeconds, String message) {
        super(message);
        this.retryAfterSeconds = retryAfterSeconds;
    }
}
