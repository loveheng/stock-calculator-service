package com.zzh.stock_calculator.crawler.embedding.service;

import com.pgvector.PGvector;
import com.zzh.stock_calculator.crawler.embedding.config.EmbeddingGate;
import com.zzh.stock_calculator.crawler.embedding.dto.AnnouncementEmbeddingHit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * announcement 向量行相似检索（ArticleEmbeddingSearchService 公告侧对偶；cls 检索下推
 * 改造同款口径）。手写 SQL 直查 vector_store：来源判别/股票/公告日区间/排除名单全部下推
 * ——杜绝「先召回后内存过滤」导致窄时段查不满/新公告挤不进 topK。
 * <p>共表判别 = {@code metadata->>'announcementId' IS NOT NULL}（cls 行无此键；公告行
 * metadata 首版即含 announcementId，B8 前存量行也可命中，kind 键冗余不判）。secCode
 * metadata 首版即有，恒可下推；annDate 为 ISO 日期文本（字典序即时间序），B8 前存量行
 * 缺失——是否下推由调用方按回填进度决定（search.retrieval.kind-filter-enabled）。</p>
 * <p>主表元数据（title/summary/secName/status 等）不在本服务回查——调用方（search 域）
 * 经 AnnouncementQueryApi 批量回查组装，本服务不引 announcement 域类型（Modulith 红线）。
 * 排序/阈值语义与 ArticleEmbeddingSearchService 一致（cosine 距离升序 + 1-distance >= threshold）。</p>
 * <p>R1：Bean 一律注册；全局 lazy-init 下仅在首个调用方注入时实例化——门控未通过时
 * embeddingModel 依赖会先触发 EmbeddingConfig 的防御性 tripwire；检索入口再行门控短路
 * 返回空集。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AnnouncementEmbeddingSearchService {

    private final EmbeddingModel embeddingModel;
    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final EmbeddingGate gate;

    /** pgvector < 0.8 无 hnsw.iterative_scan 参数：探测失败后本进程内不再重试（一次告警） */
    private final AtomicBoolean iterativeScanUnsupported = new AtomicBoolean(false);

    public List<AnnouncementEmbeddingHit> similaritySearch(String query, int topK, double threshold,
                                                           Collection<String> secCodes,
                                                           LocalDate dateFrom, LocalDate dateTo,
                                                           Collection<String> excludeAnnouncementIds) {
        if (!gate.isAvailable()) {
            // 未启用时优雅降级：返回空集（调用方空态兜底）
            log.debug("embedding unavailable, announcement similarity search returns empty");
            return List.of();
        }
        if (query == null || query.isBlank() || topK <= 0) {
            return List.of();
        }
        PGvector queryVector = new PGvector(embeddingModel.embed(query));
        LinkedHashMap<String, Double> idScores = searchVectors(queryVector, topK, threshold,
                secCodes, dateFrom, dateTo, excludeAnnouncementIds);
        List<AnnouncementEmbeddingHit> hits = new ArrayList<>(idScores.size());
        idScores.forEach((announcementId, score) -> hits.add(AnnouncementEmbeddingHit.builder()
                .announcementId(announcementId)
                .score(score)
                .build()));
        return hits;
    }

    /**
     * 事务域内执行向量检索：SET LOCAL hnsw.iterative_scan = relaxed_order 只作用于本事务
     * （连接归还池即失效，不污染其他查询），防极窄公告日区间下 HNSW post-filter 丢召回。
     */
    private LinkedHashMap<String, Double> searchVectors(PGvector queryVector, int topK, double threshold,
                                                        Collection<String> secCodes,
                                                        LocalDate dateFrom, LocalDate dateTo,
                                                        Collection<String> excludeAnnouncementIds) {
        if (iterativeScanUnsupported.get()) {
            return transactionTemplate.execute(tx ->
                    doSearch(queryVector, topK, threshold, secCodes, dateFrom, dateTo, excludeAnnouncementIds));
        }
        try {
            return transactionTemplate.execute(tx -> {
                jdbcTemplate.execute("SET LOCAL hnsw.iterative_scan = relaxed_order");
                return doSearch(queryVector, topK, threshold, secCodes, dateFrom, dateTo, excludeAnnouncementIds);
            });
        } catch (DataAccessException e) {
            if (String.valueOf(e.getMostSpecificCause().getMessage()).contains("iterative_scan")) {
                iterativeScanUnsupported.set(true);
                log.warn("hnsw.iterative_scan unsupported by current pgvector, plain index scan fallback");
                return transactionTemplate.execute(tx ->
                        doSearch(queryVector, topK, threshold, secCodes, dateFrom, dateTo, excludeAnnouncementIds));
            }
            throw e;
        }
    }

    /**
     * 向量检索 SQL：ORDER BY embedding &lt;=&gt; ? 走 HNSW(cosine) 索引；score = 1 - cosine 距离。
     * annDate 以 ISO 文本字典序比较（写入侧同序，AnnouncementEmbeddingMqService/worker 同口径）。
     */
    private LinkedHashMap<String, Double> doSearch(PGvector queryVector, int topK, double threshold,
                                                   Collection<String> secCodes,
                                                   LocalDate dateFrom, LocalDate dateTo,
                                                   Collection<String> excludeAnnouncementIds) {
        StringBuilder sql = new StringBuilder("""
                SELECT metadata->>'announcementId' AS announcement_id,
                       1 - (embedding <=> ?) AS score
                FROM vector_store
                WHERE (metadata->>'announcementId') IS NOT NULL
                """);
        List<Object> params = new ArrayList<>();
        params.add(queryVector);
        if (secCodes != null && !secCodes.isEmpty()) {
            sql.append("  AND metadata->>'secCode' IN (");
            List<String> placeholders = secCodes.stream().map(code -> "?").toList();
            sql.append(String.join(", ", placeholders));
            sql.append(")\n");
            params.addAll(secCodes);
        }
        if (dateFrom != null) {
            sql.append("  AND metadata->>'annDate' >= ?\n");
            params.add(dateFrom.toString());
        }
        if (dateTo != null) {
            sql.append("  AND metadata->>'annDate' <= ?\n");
            params.add(dateTo.toString());
        }
        if (excludeAnnouncementIds != null && !excludeAnnouncementIds.isEmpty()) {
            sql.append("  AND metadata->>'announcementId' NOT IN (");
            List<String> placeholders = excludeAnnouncementIds.stream().map(id -> "?").toList();
            sql.append(String.join(", ", placeholders));
            sql.append(")\n");
            params.addAll(excludeAnnouncementIds);
        }
        sql.append("  AND 1 - (embedding <=> ?) >= ?\n");
        sql.append("ORDER BY embedding <=> ?\nLIMIT ?");
        params.add(queryVector);
        params.add(threshold);
        params.add(queryVector);
        params.add(topK);

        return jdbcTemplate.query(sql.toString(), rs -> {
            LinkedHashMap<String, Double> idScores = new LinkedHashMap<>();
            while (rs.next()) {
                idScores.put(rs.getString("announcement_id"), rs.getDouble("score"));
            }
            return idScores;
        }, params.toArray());
    }
}
