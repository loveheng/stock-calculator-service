package com.zzh.stock_calculator.kg.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * kg.* 配置（docs/ai-pipeline/cls-news-kg.md §7）：
 * 调度已落 pull_task_config CALENDAR 行（job.kg.extract，CalendarTaskClaimScheduler 认领），
 * 本类只承载扫描/护栏键。
 */
@Data
@ConfigurationProperties(prefix = "kg")
public class KgProperties {

    private final Digest digest = new Digest();
    private final Process process = new Process();

    @Data
    public static class Digest {
        /** 汇编稿标题关键字（title LIKE %kw%；content 匹配有 24 篇噪音，勿改 content 口径） */
        private String titleKeyword = "《新闻联播》要闻";
        /** 每轮最多下发任务数（每天产 1 条，3 = 3 天追赶窗口，覆盖断档自愈） */
        private int dispatchLimit = 3;
        /** 候选扫描窗口倍数（scan = dispatch-limit * multiplier，为未终态过滤留余量） */
        private int scanMultiplier = 3;
    }

    @Data
    public static class Process {
        /** 瞬时失败计次上限，达限落 FAILED 终态 */
        private int maxFailAttempts = 5;
        /** RATE_LIMITED 上报后暂停任务发布的冷却窗口（分钟，发布端熔断） */
        private long rateLimitCooldownMinutes = 30;
    }
}
