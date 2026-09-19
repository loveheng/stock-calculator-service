package com.zzh.stock_calculator.kg.entity;

/**
 * KG 抽取任务状态（docs/ai-pipeline/cls-news-kg.md §6 状态机）：
 * 状态即游标——PENDING 行由发布器每轮重扫重发（at-least-once + 幂等摄取，D6）；
 * FAILED 为人工终态（防毒丸空耗）；DONE 后源站改稿（content_hash 变化）可回 PENDING。
 */
public enum KgTaskStatus {
    PENDING,
    DONE,
    FAILED
}
