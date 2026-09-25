package com.zzh.stock_calculator.broker.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * broker 域配置（free-canvas v3 §4.2 限流矩阵 / §3.4 请求合并 / §3.6 能力端点）。
 */
@Data
@ConfigurationProperties(prefix = "broker")
public class BrokerProperties {

    private final RateLimit rateLimit = new RateLimit();
    private final MergeCache mergeCache = new MergeCache();
    private final Indicators indicators = new Indicators();
    private final Monitor monitor = new Monitor();

    @Data
    public static class Monitor {
        /** 单用户并发监控上限（§3.5：≤5，DB 在途计数为准） */
        private int maxConcurrentPerUser = 5;
        /** 单任务判定节流间隔（秒）：调度每分钟一轮，任务级再按此节流 */
        private long minCheckIntervalSeconds = 60;
        /** 告警冷却窗（秒）：同任务两次告警最小间隔，防轰炸 */
        private long alertCooldownSeconds = 1800;
        /** 判定用 K 线回看日历日（fetch_kline from 参数） */
        private int checkLookbackDays = 7;
    }

    @Data
    public static class RateLimit {
        /** klines 桶（rl:broker:klines:{uid}，固定窗口）每窗口上限 */
        private int klinesMaxPerWindow = 30;
        /** klines 桶窗口秒数 */
        private int klinesWindowSeconds = 60;
        /** compute 桶（rl:broker:compute:{uid}）每窗口上限 */
        private int computeMaxPerWindow = 30;
        /** compute 桶窗口秒数 */
        private int computeWindowSeconds = 1;
        /** ask 桶（rl:broker:ask:{uid}，§4.2 矩阵 1/5s burst2）每窗口上限 */
        private int askMaxPerWindow = 2;
        /** ask 桶窗口秒数 */
        private int askWindowSeconds = 5;
        /** 请求体硬上限（§三统一约定：compute/ask ≤256KB，超出 413） */
        private long payloadLimitBytes = 256 * 1024L;
    }

    @Data
    public static class MergeCache {
        /** main 层短 TTL 请求合并（同 code,adjust,区间）窗口秒数，盘中多用户刷新归一 */
        private long klinesTtlSeconds = 5;
        /** 缓存最大条目数（超限触发过期清理，防长尾键膨胀） */
        private int klinesMaxEntries = 256;
    }

    @Data
    public static class Indicators {
        /** 能力版本（数字自增，前端只比对相等） */
        private int version = 1;
        /** 指标能力清单（M2 compute 白名单同源） */
        private List<Indicator> items = List.of();

        @Data
        public static class Indicator {
            private String name;
            private String label;
            /** 最少暖机根数（不足时对应指标整体返回 null） */
            private int minBars;
            private List<String> applicableBlocks = List.of();
        }
    }
}
