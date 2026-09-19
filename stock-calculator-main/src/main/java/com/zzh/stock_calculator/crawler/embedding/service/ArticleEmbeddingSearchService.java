package com.zzh.stock_calculator.crawler.embedding.service;

import com.pgvector.PGvector;
import com.zzh.stock_calculator.crawler.embedding.config.EmbeddingGate;
import com.zzh.stock_calculator.crawler.embedding.dto.ArticleEmbeddingHit;
import com.zzh.stock_calculator.crawler.entity.ClsArticle;
import com.zzh.stock_calculator.crawler.repository.ClsArticleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 相似检索服务（设计文档 §4.7，P0 service 层；cls 检索下推改造后为唯一向量查询实现）。
 * 查询经同一 EmbeddingModel 嵌入（向量空间唯一，D4）；检索改为手写 SQL 直查 vector_store：
 * ctime 区间/排除名单/共表排除全部下推（(metadata->>'xx')::bigint 数值比较），命中后按
 * metadata.articleId 批量回查 ClsArticle 组装 DTO（title/ctime 等以主表为口径）。
 * <p>为何不走 PgVectorStore.similaritySearch：SearchRequest 的 filterExpression 无法叠加
 * 会话级 hnsw.iterative_scan 防退化兜底，且窄时间窗（单日 ~0.1% 选择性）依赖该参数防
 * HNSW post-filter 丢召回；SQL 与 EmbeddingResultService 的 upsert 同款手写口径（同表）。
 * 排序/阈值语义与其保持一致（cosine 距离升序 + 1-distance >= threshold）。</p>
 * <p>R1：Bean 一律注册；全局 lazy-init 下仅在首个调用方注入时实例化——门控未通过时
 * embeddingModel 依赖会先触发 EmbeddingConfig 的防御性 tripwire；检索入口再行门控短路
 * 返回空集。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ArticleEmbeddingSearchService {

    private final EmbeddingModel embeddingModel;
    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final ClsArticleRepository articleRepository;
    private final EmbeddingGate gate;

    /** pgvector < 0.8 无 hnsw.iterative_scan 参数：探测失败后本进程内不再重试（一次告警） */
    private final AtomicBoolean iterativeScanUnsupported = new AtomicBoolean(false);

    public List<ArticleEmbeddingHit> similaritySearch(String query, int topK, double threshold,
                                                      Long ctimeFrom, Long ctimeTo,
                                                      Collection<Long> excludeArticleIds) {
        if (!gate.isAvailable()) {
            // 未启用时优雅降级：返回空集（调用方空态兜底）
            log.debug("embedding unavailable, similarity search returns empty");
            return List.of();
        }
        if (query == null || query.isBlank() || topK <= 0) {
            return List.of();
        }
        PGvector queryVector = new PGvector(embeddingModel.embed(query));
        LinkedHashMap<Long, Double> idScores = searchVectors(queryVector, topK, threshold,
                ctimeFrom, ctimeTo, excludeArticleIds);
        if (idScores.isEmpty()) {
            return List.of();
        }

        // 向量行在但主表行缺失（脏数据防御）：跳过不占名额，调用方以实际命中数判断是否补召回
        Map<Long, ClsArticle> articles = articleRepository.findAllById(idScores.keySet()).stream()
                .collect(Collectors.toMap(ClsArticle::getId, Function.identity()));
        List<ArticleEmbeddingHit> hits = new ArrayList<>(idScores.size());
        for (Map.Entry<Long, Double> entry : idScores.entrySet()) {
            ClsArticle article = articles.get(entry.getKey());
            if (article == null) {
                continue;
            }
            hits.add(ArticleEmbeddingHit.builder()
                    .articleId(article.getId())
                    .title(article.getTitle())
                    .brief(article.getBrief())
                    .content(article.getContent())
                    .level(article.getLevel())
                    .ctime(article.getCtime())
                    .score(entry.getValue())
                    .build());
        }
        return hits;
    }

    /**
     * 事务域内执行向量检索：SET LOCAL hnsw.iterative_scan = relaxed_order 只作用于本事务
     * （连接归还池即失效，不污染其他查询），防极窄时间窗下 HNSW post-filter 丢召回。
     */
    private LinkedHashMap<Long, Double> searchVectors(PGvector queryVector, int topK, double threshold,
                                                      Long ctimeFrom, Long ctimeTo,
                                                      Collection<Long> excludeArticleIds) {
        if (iterativeScanUnsupported.get()) {
            return transactionTemplate.execute(tx ->
                    doSearch(queryVector, topK, threshold, ctimeFrom, ctimeTo, excludeArticleIds));
        }
        try {
            return transactionTemplate.execute(tx -> {
                jdbcTemplate.execute("SET LOCAL hnsw.iterative_scan = relaxed_order");
                return doSearch(queryVector, topK, threshold, ctimeFrom, ctimeTo, excludeArticleIds);
            });
        } catch (DataAccessException e) {
            if (String.valueOf(e.getMostSpecificCause().getMessage()).contains("iterative_scan")) {
                iterativeScanUnsupported.set(true);
                log.warn("hnsw.iterative_scan unsupported by current pgvector, plain index scan fallback");
                return transactionTemplate.execute(tx ->
                        doSearch(queryVector, topK, threshold, ctimeFrom, ctimeTo, excludeArticleIds));
            }
            throw e;
        }
    }

    /**
     * 向量检索 SQL：ORDER BY embedding &lt;=&gt; ? 走 HNSW(cosine) 索引；score = 1 - cosine 距离。
     * 公告行无 articleId 键（NULL）被 IS NOT NULL 排除，不会触发 ::bigint 转换异常。
     */
    private LinkedHashMap<Long, Double> doSearch(PGvector queryVector, int topK, double threshold,
                                                 Long ctimeFrom, Long ctimeTo,
                                                 Collection<Long> excludeArticleIds) {
        StringBuilder sql = new StringBuilder("""
                SELECT (metadata->>'articleId')::bigint AS article_id,
                       1 - (embedding <=> ?) AS score
                FROM vector_store
                WHERE (metadata->>'articleId') IS NOT NULL
                """);
        List<Object> params = new ArrayList<>();
        params.add(queryVector);
        if (ctimeFrom != null) {
            sql.append("  AND (metadata->>'ctime')::bigint >= ?\n");
            params.add(ctimeFrom);
        }
        if (ctimeTo != null) {
            sql.append("  AND (metadata->>'ctime')::bigint <= ?\n");
            params.add(ctimeTo);
        }
        if (excludeArticleIds != null && !excludeArticleIds.isEmpty()) {
            sql.append("  AND (metadata->>'articleId')::bigint NOT IN (");
            List<String> placeholders = excludeArticleIds.stream().map(id -> "?").toList();
            sql.append(String.join(", ", placeholders));
            sql.append(")\n");
            params.addAll(excludeArticleIds);
        }
        sql.append("  AND 1 - (embedding <=> ?) >= ?\n");
        sql.append("ORDER BY embedding <=> ?\nLIMIT ?");
        params.add(queryVector);
        params.add(threshold);
        params.add(queryVector);
        params.add(topK);

        return jdbcTemplate.query(sql.toString(), rs -> {
            LinkedHashMap<Long, Double> idScores = new LinkedHashMap<>();
            while (rs.next()) {
                idScores.put(rs.getLong("article_id"), rs.getDouble("score"));
            }
            return idScores;
        }, params.toArray());
    }
}
