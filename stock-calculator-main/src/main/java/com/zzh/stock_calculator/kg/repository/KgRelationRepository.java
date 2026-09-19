package com.zzh.stock_calculator.kg.repository;

import com.zzh.stock_calculator.kg.entity.KgRelation;
import org.springframework.data.jpa.repository.JpaRepository;

/** 关系边仓库：摄取幂等靠 uq_kg_relation（含 evidence_article_id）前置 exists 判重。 */
public interface KgRelationRepository extends JpaRepository<KgRelation, Long> {

    boolean existsBySubjectEntityIdAndObjectEntityIdAndPredicateAndEvidenceArticleId(
            Long subjectEntityId, Long objectEntityId, String predicate, Long evidenceArticleId);
}
