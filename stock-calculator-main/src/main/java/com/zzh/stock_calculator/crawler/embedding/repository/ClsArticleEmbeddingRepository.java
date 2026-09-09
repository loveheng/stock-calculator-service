package com.zzh.stock_calculator.crawler.embedding.repository;

import com.zzh.stock_calculator.crawler.embedding.entity.ClsArticleEmbedding;
import com.zzh.stock_calculator.crawler.embedding.entity.EmbeddingStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 向量化状态表访问（设计文档 §4.4/§4.6）。
 * 游标即状态：pending 反连接查询按 ctime 降序取批（最新优先，跑批期间检索始终覆盖最近时段），
 * 涵盖从未处理（无状态行）与失败待重试（PENDING）两类，FAILED 终态天然排除。
 */
@Repository
public interface ClsArticleEmbeddingRepository extends JpaRepository<ClsArticleEmbedding, Long> {

    /**
     * 回填/对账游标：boundaryCtime（轮次启动快照，秒）范围内、无状态行或 PENDING 的
     * 文章 id，按发布时间降序（最新优先）。范围内清空即本轮 CAUGHT_UP 退出。
     * native @Query 属「方法名推导无法表达反连接 + LIMIT」的必要例外。
     */
    @Query(value = """
            SELECT a.id FROM cls_article a
            LEFT JOIN cls_article_embedding e ON e.article_id = a.id
            WHERE a.ctime <= :boundaryCtime
              AND (e.article_id IS NULL OR e.status = 'PENDING')
            ORDER BY a.ctime DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<Long> findPendingArticleIds(@Param("limit") int limit, @Param("boundaryCtime") long boundaryCtime);

    @Query(value = "SELECT count(*) FROM cls_article_embedding WHERE status = 'DONE'", nativeQuery = true)
    long countDone();

    @Query(value = "SELECT count(*) FROM cls_article", nativeQuery = true)
    long countArticles();

    /** 按状态计数（FAILED 终态数用于完成判定与完成通知） */
    long countByStatus(EmbeddingStatus status);

    /** 窗口内完成嵌入数（回填 + 增量共用 embedded_at，统计报告用） */
    long countByEmbeddedAtGreaterThanEqual(OffsetDateTime since);

    /**
     * 窗口内新增电报（ctime >= sinceCtime）中尚未完成嵌入数：无状态行或 PENDING。
     * native @Query 属「方法名推导无法表达反连接」的必要例外（同游标查询）。
     */
    @Query(value = """
            SELECT count(*) FROM cls_article a
            LEFT JOIN cls_article_embedding e ON e.article_id = a.id
            WHERE a.ctime >= :sinceCtime
              AND (e.article_id IS NULL OR e.status = 'PENDING')
            """, nativeQuery = true)
    long countNewPendingArticles(@Param("sinceCtime") long sinceCtime);
}
