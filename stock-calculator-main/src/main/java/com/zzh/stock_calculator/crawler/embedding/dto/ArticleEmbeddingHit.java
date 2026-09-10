package com.zzh.stock_calculator.crawler.embedding.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * cls_article 相似检索命中项（设计文档 §4.7）。
 * score 为 cosine 相似度（1 - 距离），越高越相关。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ArticleEmbeddingHit {

    private Long articleId;

    private String title;

    private String brief;

    /** 正文（summary 兑底来源，brief 缺失时截断使用） */
    private String content;

    private String level;

    /** 原始发布时间戳（秒） */
    private Long ctime;

    /** 相似度得分 [0,1] */
    private Double score;
}
