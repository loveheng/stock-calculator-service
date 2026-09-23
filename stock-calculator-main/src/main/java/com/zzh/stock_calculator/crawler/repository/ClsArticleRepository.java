package com.zzh.stock_calculator.crawler.repository;
import com.zzh.stock_calculator.crawler.entity.ClsArticle;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.domain.Limit;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface ClsArticleRepository extends JpaRepository<ClsArticle, Long> {

    // 获取时间最大（最新）的一条完整记录
    Optional<ClsArticle> findFirstByOrderByCtimeDesc();

    @Query("SELECT a.ctime  FROM ClsArticle a where a.ctime between :startTime and :endTime order by ctime asc limit 1")
    Long findHistoryMinCtime(@Param("startTime") Long startTime,@Param("endTime") Long endTime);

    /** 窗口内新增电报数（按发布时间 ctime，秒；统计报告用，爬虫近实时入库 ctime ≈ 入库时间） */
    long countByCtimeGreaterThanEqual(Long ctime);

    /** 按主键批量取电报（news-kg 时间轴日头场景：≤ pageSize 量级，id 集合来自图谱聚合） */
    List<ClsArticle> findByIdIn(Collection<Long> ids);

    /**
     * 关键词精确检索（短查询路由路径）：content 子串匹配（LIKE '%kw%'，走 pg_trgm GIN 索引），
     * ctime 区间可选（dateRange 存在时成对传入，否则双 null 全时段），按发布时间倒序取前 limit 条。
     * 语义=「精确认领实体/关键词」，无相关性阈值；实体判定与路由见 ClsArticleQueryApi.isEntityLikeQuery。
     */
    @Query("""
            SELECT a FROM ClsArticle a
            WHERE a.content LIKE concat('%', :keyword, '%')
              AND (:fromCtime IS NULL OR a.ctime >= :fromCtime)
              AND (:toCtime IS NULL OR a.ctime <= :toCtime)
            ORDER BY a.ctime DESC
            """)
    List<ClsArticle> searchByContentKeyword(@Param("keyword") String keyword,
                                            @Param("fromCtime") Long fromCtime,
                                            @Param("toCtime") Long toCtime,
                                            Limit limit);

    /**
     * 标题含关键字的最新电报（news-kg 汇编扫描：title LIKE %kw%，ctime 倒序取前 limit 条；
     * 走 idx_cls_article_ctime 序扫描过滤，命中每天 1 条的汇编稿成本可忽略）。
     */
    List<ClsArticle> findByTitleContainingOrderByCtimeDesc(String title, Limit limit);

    /**
     * 标题含关键字的最旧电报（news-kg 历史回填扫描：title LIKE %kw%，ctime 正序取前 limit 条，
     * excludeIds 为 kg 侧 DONE 任务行排除集——扫描只回未处理稿，窗口才真正随终态累积前滑，
     * 修复「最旧 N 条被终态占满 → 回填空转」死锁）。
     * 注意：excludeIds 为空时不得调用本方法（NOT IN () 非法 SQL），由调用方分流到
     * {@link #findByTitleContainingOrderByCtimeAsc}（Hibernate 7 不支持对集合参数用 IS EMPTY）。
     */
    @Query("""
            SELECT a FROM ClsArticle a
            WHERE a.title LIKE concat('%', :title, '%')
              AND a.id NOT IN :excludeIds
            ORDER BY a.ctime ASC
            """)
    List<ClsArticle> findOldestByTitleExcludingIds(@Param("title") String title,
                                                   @Param("excludeIds") Collection<Long> excludeIds,
                                                   Limit limit);
}
