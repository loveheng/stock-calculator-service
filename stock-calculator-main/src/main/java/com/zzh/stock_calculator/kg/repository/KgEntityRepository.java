package com.zzh.stock_calculator.kg.repository;

import com.zzh.stock_calculator.kg.dto.KgQueryDtos.EntitySuggestView;
import com.zzh.stock_calculator.kg.dto.KgQueryDtos.RelatedEntityView;
import com.zzh.stock_calculator.kg.entity.KgEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * 实体仓库：锚点实体按 uq_kg_entity_anchor 定位，自由实体按 uq_kg_entity_type_name 定位；
 * 查询侧提供实体检索建议与同事件共现实体聚合（KG 实体优先检索路径）。
 */
public interface KgEntityRepository extends JpaRepository<KgEntity, Long> {

    Optional<KgEntity> findByAnchorTypeAndAnchorId(String anchorType, String anchorId);

    Optional<KgEntity> findByEntityTypeAndName(String entityType, String name);

    /**
     * 实体检索建议/命中（name 或别名 ILIKE，提及次数倒序——热实体优先，
     * 别名检索走 jsonb::text 子串）。keyword 为调用方拼好的 %kw% pattern。
     */
    @Query(value = """
            SELECT id AS "id", name AS "name", entity_type AS "entityType",
                   anchor_type AS "anchorType", mention_count AS "mentionCount"
            FROM kg_entity
            WHERE status = 'ACTIVE'
              AND (name ILIKE :keyword OR aliases::text ILIKE :keyword)
            ORDER BY mention_count DESC, last_seen_at DESC NULLS LAST
            LIMIT :limit
            """, nativeQuery = true)
    List<EntitySuggestView> searchSuggest(@Param("keyword") String keyword,
                                          @Param("limit") int limit);

    /**
     * 高频共现实体：同事件共现计数倒序（时间轴实体摘要卡的「关联实体」chips）；
     * 计数并列时按实体自身热度兜底排序。
     */
    @Query(value = """
            SELECT en.id AS "id", en.name AS "name", en.entity_type AS "entityType",
                   en.anchor_type AS "anchorType", COUNT(*) AS "coMentionCount"
            FROM kg_event_entity l1
            JOIN kg_event_entity l2 ON l2.event_id = l1.event_id AND l2.entity_id <> l1.entity_id
            JOIN kg_entity en ON en.id = l2.entity_id
            WHERE l1.entity_id = :entityId
            GROUP BY en.id, en.name, en.entity_type, en.anchor_type, en.mention_count
            ORDER BY COUNT(*) DESC, en.mention_count DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<RelatedEntityView> findRelatedEntities(@Param("entityId") Long entityId,
                                                @Param("limit") int limit);
}
