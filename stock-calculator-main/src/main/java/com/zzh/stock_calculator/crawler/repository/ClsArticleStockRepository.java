package com.zzh.stock_calculator.crawler.repository;
import com.zzh.stock_calculator.crawler.entity.ClsArticleStock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface ClsArticleStockRepository extends JpaRepository<ClsArticleStock, Long> {

    /** 批量取文章-股票关联（search 域 mention 组装，经 ClsArticleQueryApi 门面上提） */
    List<ClsArticleStock> findByArticleIdIn(Collection<Long> articleIds);

    /** 指定股票自 sinceCtime（秒）起被提及的文章数（P2 clsMention 预留；原生 SQL 避免无关联实体 join 的 HQL 兼容问题） */
    @Query(value = "SELECT COUNT(DISTINCT cas.article_id) FROM cls_article_stock cas "
            + "JOIN cls_article a ON a.id = cas.article_id "
            + "WHERE cas.stock_id = :stockId AND a.ctime >= :sinceCtime", nativeQuery = true)
    long countDistinctArticleIdByStockIdSince(@Param("stockId") String stockId,
                                              @Param("sinceCtime") long sinceCtime);

    /** 指定股票近窗口被提及的文章 id（ctime 倒序截断；guide 引导依据/档案，docs/guide/design.md §五） */
    @Query(value = "SELECT cas.article_id FROM cls_article_stock cas "
            + "JOIN cls_article a ON a.id = cas.article_id "
            + "WHERE cas.stock_id = :stockId AND a.ctime >= :sinceCtime "
            + "ORDER BY a.ctime DESC LIMIT :limit", nativeQuery = true)
    List<Long> findArticleIdsByStockIdSince(@Param("stockId") String stockId,
                                            @Param("sinceCtime") long sinceCtime,
                                            @Param("limit") int limit);

    /** 指定股票近期提及的题材聚合（反向两跳：文章同源带出题材；guide 引导档案标签） */
    @Query(value = "SELECT s.subject_id AS subjectId, COUNT(DISTINCT s.article_id) AS articleCount "
            + "FROM cls_article_stock cas "
            + "JOIN cls_article a ON a.id = cas.article_id "
            + "JOIN cls_article_subject s ON s.article_id = cas.article_id "
            + "WHERE cas.stock_id = :stockId AND a.ctime >= :sinceCtime "
            + "GROUP BY s.subject_id "
            + "ORDER BY articleCount DESC LIMIT :limit", nativeQuery = true)
    List<SubjectArticleCountView> aggregateSubjectsByStockIdSince(@Param("stockId") String stockId,
                                                                  @Param("sinceCtime") long sinceCtime,
                                                                  @Param("limit") int limit);

    /** native 聚合投影（Spring Data 接口投影，列别名对齐 getter） */
    interface SubjectArticleCountView {
        Long getSubjectId();

        long getArticleCount();
    }
}
