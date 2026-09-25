package com.zzh.stock_calculator.crawler.repository;
import com.zzh.stock_calculator.crawler.entity.ClsArticleSubject;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface ClsArticleSubjectRepository extends JpaRepository<ClsArticleSubject, Long> {

    /** 批量取文章-题材关联（E2E 幂等校验用，风格对齐 ClsArticleStockRepository） */
    List<ClsArticleSubject> findByArticleIdIn(Collection<Long> articleIds);

    /** 题材近窗口活跃股票聚合（两跳：题材→文章→提及股票；guide 引导题材锚点扩展，docs/guide/design.md §五） */
    @Query(value = "SELECT k.stock_id AS stockId, COUNT(DISTINCT cas.article_id) AS articleCount "
            + "FROM cls_article_subject cas "
            + "JOIN cls_article a ON a.id = cas.article_id "
            + "JOIN cls_article_stock k ON k.article_id = cas.article_id "
            + "WHERE cas.subject_id = :subjectId AND a.ctime >= :sinceCtime "
            + "GROUP BY k.stock_id "
            + "ORDER BY articleCount DESC LIMIT :limit", nativeQuery = true)
    List<StockMentionCountView> aggregateActiveStocksBySubjectIdSince(
            @Param("subjectId") long subjectId,
            @Param("sinceCtime") long sinceCtime,
            @Param("limit") int limit);

    /** native 聚合投影（Spring Data 接口投影，列别名对齐 getter） */
    interface StockMentionCountView {
        String getStockId();

        long getArticleCount();
    }
}
