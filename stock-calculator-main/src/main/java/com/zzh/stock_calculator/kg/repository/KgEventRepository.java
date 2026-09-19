package com.zzh.stock_calculator.kg.repository;

import com.zzh.stock_calculator.kg.entity.KgEvent;
import org.springframework.data.jpa.repository.JpaRepository;

/** 事件仓库：事件级判重靠 uq_kg_event_article_hash 前置 exists。 */
public interface KgEventRepository extends JpaRepository<KgEvent, Long> {

    boolean existsByArticleIdAndContentHash(Long articleId, String contentHash);
}
