package com.zzh.stock_calculator.crawler.embedding.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * announcement 向量行相似检索命中项（ArticleEmbeddingHit 公告侧对偶）。
 * 仅携带 vector_store metadata 键（主表元数据由调用方经 AnnouncementQueryApi 回查组装）。
 * score 为 cosine 相似度（1 - 距离），越高越相关。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AnnouncementEmbeddingHit {

    /** CNINFO announcementId（vector_store metadata；cls 行无此键，公告行首版即有） */
    private String announcementId;

    /** 相似度得分 [0,1] */
    private Double score;
}
