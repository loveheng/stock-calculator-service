package com.zzh.stock_calculator.crawler.embedding.config;

import lombok.Data;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 向量化配置（embedding.* 前缀，设计文档 §7.1）。
 * native 变体不配置本块 → enabled 默认 false，整体关闭（D9）。
 */
@Data
@ConfigurationProperties(prefix = "embedding")
public class EmbeddingProperties {

    /** 功能总开关；配合 account-id/api-token 非空三重条件装配（EmbeddingEnabledCondition） */
    private boolean enabled = false;

    private final Cloudflare cloudflare = new Cloudflare();

    /** 回填游标批大小（每小时取批条数） */
    private int batchSize = 64;

    /** 批间节流间隔毫秒，避免误触 RPM 类限额 */
    private long batchIntervalMs = 300;

    /** 单日回填上限（保险丝；仅约束回填 Task，增量监听不占此计数器）。
     *  60000 条 ≈ 免费额度 10000 Neurons 的 75%~84%（bge-m3 计价 1075 Neurons/M tokens
     *  × 均值 ~115 tokens/条），余 ~20% 防回填打爆当日额度致增量/聊天查询连坐 429。 */
    private int dailyMaxArticles = 60000;

    /** 永久性失败连续次数上限：达上限落 FAILED 终态（仅 PERMANENT 类计次，成功清零） */
    private int maxFailAttempts = 5;

    private final Backfill backfill = new Backfill();

    private final Report report = new Report();

    private final Search search = new Search();

    @Data
    public static class Cloudflare {

        /** CF 账户 ID，拼接 OpenAI 兼容端点 base-url */
        private String accountId;

        /** CF API Token（日志脱敏） */
        @ToString.Exclude
        private String apiToken;

        /** Workers AI embedding 模型 */
        private String model = "@cf/baai/bge-m3";

        /** 向量维度（bge-m3 固定 1024） */
        private int dimensions = 1024;

        /** 单次 HTTP 请求超时 */
        private Duration readTimeout = Duration.ofSeconds(30);
    }

    @Data
    public static class Backfill {

        /** 启动后延迟触发首轮回填 */
        private Duration startupDelay = Duration.ofSeconds(15);

        /** 回填 cron（UTC 时区由 @Scheduled zone 显式指定） */
        private String cron = "0 5 * * * *";
    }

    @Data
    public static class Report {

        /** 统计报告开关（实际发送还需 EMBEDDING_NOTIFY_EMAIL 配置收件人） */
        private boolean enabled = true;

        /** 发送间隔天数（每日 cron 检查点比对上次发送，满间隔才发） */
        private int intervalDays = 3;

        /** 每日检查 cron（UTC；默认 01:00 = 北京 09:00） */
        private String cron = "0 0 1 * * *";
    }

    @Data
    public static class Search {

        private int defaultTopK = 10;

        /** cosine 相似度阈值，P1 场景联调时校准 */
        private double defaultThreshold = 0.5;
    }
}
