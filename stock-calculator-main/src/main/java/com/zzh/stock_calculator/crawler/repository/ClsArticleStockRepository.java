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
}
