package com.zzh.stock_calculator.crawler;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 存量回填完成事件（crawler 模块 API，放基包供跨模块监听 —— Modulith 基包规则）。
 *
 * <p>由 EmbeddingBackfillTask 在「全量 DONE/FAILED」的完成迁移点发布（进程生命周期内
 * 一次，重启前已完成则预置标志不重发）；auth 侧监听发送完成通知邮件。无监听方或
 * 邮件未配置时静默，不影响回填任务。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EmbeddingBackfillCompletedEvent {

    /** 文章总数 */
    private long totalArticles;

    /** 已向量化数 */
    private long doneCount;

    /** 永久失败终态数 */
    private long failedCount;

    /** 完成本轮耗时毫秒 */
    private long elapsedMs;
}
