package com.zzh.stock_calculator.copilot.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 异步任务通道配置（步 6-4a 限流阈值，memory 定案③）：
 * 并发上限与频控窗口全部可配；默认值按评审口径（同时 2 个长任务 / 60 次/分）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "copilot.async-task")
public class AsyncTaskProperties {

    private final RateLimit rateLimit = new RateLimit();

    @Data
    public static class RateLimit {
        /** 用户在途（RUNNING）任务并发上限 */
        private int maxConcurrent = 2;
        /** 频控时间窗（秒） */
        private int frequencyWindowSeconds = 60;
        /** 时间窗内最大创建次数 */
        private int maxPerWindow = 60;
    }
}
