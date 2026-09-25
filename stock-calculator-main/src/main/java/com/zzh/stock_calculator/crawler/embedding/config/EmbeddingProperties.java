package com.zzh.stock_calculator.crawler.embedding.config;

import lombok.Data;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 向量化配置（embedding.* 前缀，设计文档 §7.1）。
 * 未配置本块时 enabled 默认 false，整体关闭；enabled=true 但凭据缺失时同样关闭并
 * WARN 一次（运行期 EmbeddingGate 判定，native/JVM 行为一致，R1）。
 * 供应商连接规格已迁 ai.embeddings.openai-embed（stock-calculator-llm 组件，不绑 CF）。
 */
@Data
@ConfigurationProperties(prefix = "embedding")
public class EmbeddingProperties {

    /** 功能总开关；配合 embed tier 三键齐备双门控（EmbeddingGate，运行期判定） */
    private boolean enabled = false;

    /** 单日下发上限（保险丝；发布端统一记账——增量下发与对账扫缺都经 tryAcquireBackfill）。
     *  60000 条 ≈ 免费额度 10000 Neurons 的 75%~84%（bge-m3 计价 1075 Neurons/M tokens
     *  × 均值 ~115 tokens/条），余 ~20% 防回填打爆当日额度致增量/聊天查询连坐 429。 */
    private int dailyMaxArticles = 60000;

    /** 永久性失败连续次数上限：达上限落 FAILED 终态（仅 PERMANENT 类计次，成功清零） */
    private int maxFailAttempts = 5;

    private final Backfill backfill = new Backfill();

    private final Report report = new Report();

    private final Search search = new Search();

    @Data
    public static class Backfill {

        /** 回填对账总开关（startup/DB 调度两条触发路径都停；E2E 共享 broker 场景防真实库任务污染测试队列） */
        private boolean enabled = true;

        /** 启动后延迟触发首轮回填 */
        private Duration startupDelay = Duration.ofSeconds(15);
    }

    @Data
    public static class Report {

        /** 统计报告开关（实际发送还需 EMBEDDING_NOTIFY_EMAIL 配置收件人） */
        private boolean enabled = true;

        /** 发送间隔天数（每日检查点比对上次发送，满间隔才发） */
        private int intervalDays = 3;
    }

    @Data
    public static class Search {

        private int defaultTopK = 10;

        /** cosine 相似度阈值，P1 场景联调时校准 */
        private double defaultThreshold = 0.5;
    }
}
