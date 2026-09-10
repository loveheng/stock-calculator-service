package com.zzh.stock_calculator.search.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 搜索域配置（prefix=search，backend-implementation §9）。
 * <p>拍板值：限流 检索 10 次/10s、综合 3 次/10s（B6）；检索初始参数 threshold=0.3 / topK=10（C11，
 * crawler 侧 embedding.search.default-threshold=0.5 不受影响，搜索独立校准）。</p>
 */
@Data
@ConfigurationProperties(prefix = "search")
public class SearchProperties {

    private final RateLimit rateLimit = new RateLimit();
    private final Retrieval retrieval = new Retrieval();
    private final Composite composite = new Composite();

    @Data
    public static class RateLimit {
        /** 固定限流窗口（秒），检索/综合共用 */
        private int searchWindowSeconds = 10;
        /** 窗口内检索类端点（announcements / cls / stock-profile）上限 */
        private int searchMaxPerWindow = 10;
        /** 窗口内综合摘要请求上限 */
        private int compositeMaxPerWindow = 3;
    }

    @Data
    public static class Retrieval {
        private int defaultTopK = 10;
        private int maxTopK = 50;
        private double defaultThreshold = 0.3;
        /**
         * 来源过滤模式（backend-implementation §2 步骤 2）：
         * false = 过渡期（回填前）：topK 放大 + 回查 AnnouncementQueryApi 过滤（vector_store 尚无 kind 元数据）；
         * true  = 回填完成后：filterExpression 下推 kind=='announcement' + annDate 区间。
         */
        private boolean kindFilterEnabled = false;
    }

    @Data
    public static class Composite {
        /** LLM 生成预算（秒）；SseEmitter timeout 在此基础上放宽（§4.5，勿照抄 copilot 300s） */
        private int llmTimeoutSeconds = 30;
        /** 综合摘要双库检索 topK */
        private int retrievalTopK = 8;
    }
}
