package com.zzh.stock_calculator.common;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 429 信封 data 载荷（backend-implementation §5.3）：前端按 retryAfterSeconds 展示倒计时；
 * 对无 data 的存量 429（copilot/auth），前端已有缺省兜底分支。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RateLimitData {

    /** 距窗口重置的秒数（= windowSeconds - epochSeconds % windowSeconds） */
    private long retryAfterSeconds;
}
