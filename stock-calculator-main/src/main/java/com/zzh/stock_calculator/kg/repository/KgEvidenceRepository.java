package com.zzh.stock_calculator.kg.repository;

import com.zzh.stock_calculator.kg.entity.KgEvidence;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/** 证据行仓库：UNIQUE(article_id) 语义下按文章定位单一证据（摄取 upsert / 重放入口）。 */
public interface KgEvidenceRepository extends JpaRepository<KgEvidence, Long> {

    Optional<KgEvidence> findByArticleId(Long articleId);
}
