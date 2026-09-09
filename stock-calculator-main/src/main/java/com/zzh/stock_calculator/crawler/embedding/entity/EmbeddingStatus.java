package com.zzh.stock_calculator.crawler.embedding.entity;

/**
 * 向量化状态三态枚举（设计文档 §3.2）。
 * 不存在行 或 PENDING = 未处理/待重试；DONE = 已生成；FAILED = 永久失败终态
 * （PERMANENT 类失败计次达上限，退出回填游标）；「处理中」仅为内存游标，不落库。
 */
public enum EmbeddingStatus {
    PENDING,
    DONE,
    FAILED
}
