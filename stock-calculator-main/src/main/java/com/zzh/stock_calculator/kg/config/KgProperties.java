package com.zzh.stock_calculator.kg.config;

import com.zzh.stockcalc.contract.KgControlledVocabulary;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/**
 * kg.* 配置（docs/ai-pipeline/cls-news-kg.md §7）：
 * 调度已落 pull_task_config CALENDAR 行（job.kg.extract / job.kg.backfill，
 * CalendarTaskClaimScheduler 认领），本类只承载扫描/护栏键。
 */
@Data
@ConfigurationProperties(prefix = "kg")
public class KgProperties {

    private final Digest digest = new Digest();
    private final Process process = new Process();
    private final Backfill backfill = new Backfill();
    private final Fuse fuse = new Fuse();

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

    @Data
    public static class Backfill {
        /** 回填总开关（startup/DB 调度两条触发路径都停；E2E 共享 broker 场景防真实库任务污染） */
        private boolean enabled = true;
        /** 每轮最多下发任务数（1114 条存量按 ctime 最旧优先分批，30 分钟一轮 × 20 ≈ 1.2 天追平） */
        private int batchSize = 20;
        /** 回填扫描窗口倍数（scan = batch-size * multiplier，为未终态过滤留余量） */
        private int scanMultiplier = 3;
        /** 启动后延迟触发首轮回填（与 DB 调度互补，单飞守卫防重叠） */
        private Duration startupDelay = Duration.ofSeconds(15);
    }

    @Data
    public static class Fuse {
        /**
         * 关系谓词受控词表（docs §8 prompt 规则 4 / 契约 KgExtraction.Relation.predicate）：
         * 表外谓词一律归一为兜底值——模型偶发自造谓词（出席/显示/推动…），入库不归一会让同一
         * 语义裂成多种写法、按谓词聚合即失真。
         *
         * <p>默认值取自 contract 的 {@code KgControlledVocabulary}（与 data 侧 SYSTEM_PROMPT
         * 同一份 SSOT）；yml 默认不覆盖此项，确需单环境收窄词表时才显式配置。</p>
         */
        private List<String> predicateWhitelist = KgControlledVocabulary.PREDICATES;
        /** 表外谓词的归一目标值（必须属于上表，否则归一后仍会被判为越界） */
        private String predicateFallback = KgControlledVocabulary.PREDICATE_FALLBACK;
    }
}
