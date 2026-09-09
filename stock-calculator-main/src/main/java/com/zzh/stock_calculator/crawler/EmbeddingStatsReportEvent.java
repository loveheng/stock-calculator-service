package com.zzh.stock_calculator.crawler;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 向量化统计报告事件（crawler 模块 API，放基包供跨模块监听 —— Modulith 基包规则）。
 *
 * <p>由 EmbeddingStatsReportTask 按配置间隔（默认 3 天）统计后发布；auth 侧监听渲染
 * 文本并发送统计报告邮件。存量回填完成后（backfillComplete=true）监听器仅输出增量段。
 * 无监听方或邮件未配置时静默，不影响统计任务。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EmbeddingStatsReportEvent {

    /** 统计窗口起点（epoch 秒，含；= 上次发送时刻） */
    private long windowStartEpochSecond;

    /** 统计窗口终点（epoch 秒，含；= 本次发送时刻） */
    private long windowEndEpochSecond;

    /** 窗口内新增电报数（按 ctime） */
    private long newArticleCount;

    /** 窗口内新增电报中尚未完成嵌入数（无状态行或 PENDING） */
    private long newPendingCount;

    /** 文章总数 */
    private long totalArticles;

    /** 已向量化数（全局累计） */
    private long doneCount;

    /** 永久失败终态数（全局累计） */
    private long failedCount;

    /** 窗口内完成嵌入数（回填 + 增量共用，按 embedded_at） */
    private long embeddedInWindow;

    /** 存量回填是否已全量完成（DONE + FAILED >= 总数，与回填 Task 判定同式） */
    private boolean backfillComplete;
}
