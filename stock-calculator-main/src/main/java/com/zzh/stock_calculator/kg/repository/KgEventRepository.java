package com.zzh.stock_calculator.kg.repository;

import com.zzh.stock_calculator.kg.dto.KgQueryDtos.EventDayAgg;
import com.zzh.stock_calculator.kg.entity.KgEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;

/**
 * 事件仓库：事件级判重靠 uq_kg_event_article_hash 前置 exists；
 * 查询侧为时间轴卡片流提供日聚合/过滤事件两段原生 SQL（跨 KgEventLink/KgEntity 的
 * EXISTS 子查询用 theta 写法——实体间无 JPA 关联，且别名 jsonb 检索必须下到 SQL）。
 * 过滤参数统一「null = 不限」，pattern 由调用方拼 %kw%。
 * <p>红线：可空参数出现在 IS NULL 位必须显式 CAST 定型——PG 无法从「? IS NULL」
 * 推断参数类型（42P18 could not determine data type of parameter）。实测两条绑定路径
 * 行为不同：JVM 动态代理下 temporal null untyped（String/Long 自带类型绑定）；native AOT
 * （KgEventRepositoryImpl__AotRepository）下 temporal/String null 均 untyped（Long 未单独
 * 验证）——故 keyword/entityId/eventType/fromTime/toTime 五参在 IS NULL 位全部 CAST，
 * 一律覆盖任意绑定路径（CAST 对 PG 语义无副作用，比对位可由列/算子自行推断无需加）。</p>
 */
public interface KgEventRepository extends JpaRepository<KgEvent, Long> {

    boolean existsByArticleIdAndContentHash(Long articleId, String contentHash);

    /**
     * 日聚合分页（时间轴主干）：按汇编稿分组，组序 = 组内最新事件时间倒序（回填乱序插入
     * 也不影响时间轴时序），空 event_time 的组沉底（回填修复后应趋近于零）。
     * keyword 三路命中：事件文本（title/detail）或关联实体名/别名（别名检索走
     * jsonb::text ILIKE，量级 ~3 万行全扫可忽略）。
     */
    @Query(value = """
            SELECT e.article_id AS "articleId",
                   COUNT(*) AS "eventCount",
                   MAX(e.event_time) AS "dayTime"
            FROM kg_event e
            WHERE (CAST(:keyword AS text) IS NULL OR e.title ILIKE :keyword OR e.detail ILIKE :keyword
                   OR EXISTS (SELECT 1 FROM kg_event_entity l
                              JOIN kg_entity en ON en.id = l.entity_id
                              WHERE l.event_id = e.id
                                AND (en.name ILIKE :keyword OR en.aliases::text ILIKE :keyword)))
              AND (CAST(:entityId AS bigint) IS NULL OR EXISTS (SELECT 1 FROM kg_event_entity l2
                              WHERE l2.event_id = e.id AND l2.entity_id = :entityId))
              AND (CAST(:eventType AS text) IS NULL OR e.event_type = :eventType)
              AND (CAST(:fromTime AS timestamptz) IS NULL OR e.event_time >= CAST(:fromTime AS timestamptz))
              AND (CAST(:toTime AS timestamptz) IS NULL OR e.event_time < CAST(:toTime AS timestamptz))
            GROUP BY e.article_id
            ORDER BY MAX(e.event_time) DESC NULLS LAST, e.article_id DESC
            LIMIT :pageSize OFFSET :offset
            """, nativeQuery = true)
    List<EventDayAgg> findDayAggregates(@Param("keyword") String keyword,
                                        @Param("entityId") Long entityId,
                                        @Param("eventType") String eventType,
                                        @Param("fromTime") OffsetDateTime fromTime,
                                        @Param("toTime") OffsetDateTime toTime,
                                        @Param("pageSize") int pageSize,
                                        @Param("offset") int offset);

    /** 同 findDayAggregates 过滤口径的命中日总数 */
    @Query(value = """
            SELECT COUNT(DISTINCT e.article_id)
            FROM kg_event e
            WHERE (CAST(:keyword AS text) IS NULL OR e.title ILIKE :keyword OR e.detail ILIKE :keyword
                   OR EXISTS (SELECT 1 FROM kg_event_entity l
                              JOIN kg_entity en ON en.id = l.entity_id
                              WHERE l.event_id = e.id
                                AND (en.name ILIKE :keyword OR en.aliases::text ILIKE :keyword)))
              AND (CAST(:entityId AS bigint) IS NULL OR EXISTS (SELECT 1 FROM kg_event_entity l2
                              WHERE l2.event_id = e.id AND l2.entity_id = :entityId))
              AND (CAST(:eventType AS text) IS NULL OR e.event_type = :eventType)
              AND (CAST(:fromTime AS timestamptz) IS NULL OR e.event_time >= CAST(:fromTime AS timestamptz))
              AND (CAST(:toTime AS timestamptz) IS NULL OR e.event_time < CAST(:toTime AS timestamptz))
            """, nativeQuery = true)
    long countDistinctArticle(@Param("keyword") String keyword,
                              @Param("entityId") Long entityId,
                              @Param("eventType") String eventType,
                              @Param("fromTime") OffsetDateTime fromTime,
                              @Param("toTime") OffsetDateTime toTime);

    /**
     * 取本页各日的命中事件（SELECT e.* 整行映射回实体；组内排序由 Service 按
     * 「event_time 降序空值沉底、id 降序」最新在前重排——时间同为日期零点时后融合者在前）。
     */
    @Query(value = """
            SELECT e.* FROM kg_event e
            WHERE e.article_id IN (:articleIds)
              AND (CAST(:keyword AS text) IS NULL OR e.title ILIKE :keyword OR e.detail ILIKE :keyword
                   OR EXISTS (SELECT 1 FROM kg_event_entity l
                              JOIN kg_entity en ON en.id = l.entity_id
                              WHERE l.event_id = e.id
                                AND (en.name ILIKE :keyword OR en.aliases::text ILIKE :keyword)))
              AND (CAST(:entityId AS bigint) IS NULL OR EXISTS (SELECT 1 FROM kg_event_entity l2
                              WHERE l2.event_id = e.id AND l2.entity_id = :entityId))
              AND (CAST(:eventType AS text) IS NULL OR e.event_type = :eventType)
              AND (CAST(:fromTime AS timestamptz) IS NULL OR e.event_time >= CAST(:fromTime AS timestamptz))
              AND (CAST(:toTime AS timestamptz) IS NULL OR e.event_time < CAST(:toTime AS timestamptz))
            """, nativeQuery = true)
    List<KgEvent> findFilteredByArticleIds(@Param("articleIds") Collection<Long> articleIds,
                                           @Param("keyword") String keyword,
                                           @Param("entityId") Long entityId,
                                           @Param("eventType") String eventType,
                                           @Param("fromTime") OffsetDateTime fromTime,
                                           @Param("toTime") OffsetDateTime toTime);
}
