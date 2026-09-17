package com.zzh.stock_calculator.copilot.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Copilot 记忆固化链路参数（copilot.memory.* 前缀，docs/copilot/memory-profile.md §五/§七/§十一 5）。
 */
@Data
@ConfigurationProperties(prefix = "copilot.memory")
public class CopilotMemoryProperties {

    /** 记忆链总开关（关闭时种子不发布、tick 直接丢弃，既有聊天行为零变化） */
    private boolean enabled = true;

    /** 轻量种子 per-message TTL（毫秒）＝ 合批窗：窗内连发多轮仅 1 次提炼 */
    private long seedTtlMs = 60_000;

    /** 提炼在途锁超时兜底（毫秒）：在途任务丢失（worker 崩/result 丢）后放行重发，防死锁 */
    private long inFlightTimeoutMs = 30_000;

    /** 差量片段截断上限（字符，含「[已截断]」尾标，决策 #11） */
    private int snippetMaxChars = 2000;

    /** source_message_ids 溯源保留上限（防膨胀，§四） */
    private int sourceIdsKeep = 20;

    /** 画像触发：ΔCount（updated_at > 游标的 active 条数）阈值（决策 #18） */
    private int profileDeltaThreshold = 3;

    /** 画像输入治理：per-topic ctime 倒序 Top-M（决策 #17） */
    private int profileTopM = 4;

    /** 画像权重：topic 内位次线性衰减系数（weight = max(0, 1 − decay × 位次差)，最新 1.0） */
    private double profileWeightDecay = 0.25;

    /** 稀疏冷启动护栏（决策 #22）：active 条数低于此值时 personality/deepPreferences 强制空
     *  （main 入库侧确定性兑底，与 worker prompt 护栏双保险） */
    private int profileSparseGuardMin = 3;

    /** 注入预算族（每次聊天恒定成本，§七） */
    private Recall recall = new Recall();

    @Data
    public static class Recall {

        /** 画像段预算（字符） */
        private int profileBudget = 800;

        /** 置顶记忆段预算（字符） */
        private int pinnedBudget = 1200;

        /** 窗口记忆段预算（字符） */
        private int memoryBudget = 2000;

        /** 近期历史段预算（字符） */
        private int recentBudget = 2000;

        /** 近期历史时间窗（天，排除当前会话） */
        private int recentDays = 3;

        /** 窗口记忆段条目上限 top-N */
        private int topN = 20;

        /** 置顶条目上限（§八：置顶上限 10 条） */
        private int pinnedMax = 10;
    }
}
