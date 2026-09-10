package com.zzh.stock_calculator.crawler.embedding.service;

import com.zzh.stock_calculator.crawler.embedding.config.EmbeddingGate;
import com.zzh.stock_calculator.crawler.embedding.config.EmbeddingProperties;
import com.zzh.stock_calculator.crawler.embedding.dto.ArticleEmbeddingHit;
import com.zzh.stock_calculator.crawler.entity.ClsArticle;
import com.zzh.stock_calculator.crawler.repository.ClsArticleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 相似检索服务（设计文档 §4.7，P0 service 层）。
 * 查询经同一 EmbeddingModel 嵌入（向量空间唯一，D4），由 VectorStore 内部完成；
 * 命中项按 metadata.articleId 批量回查 ClsArticle 组装 DTO。
 * P1 场景接入时上提门面至 crawler 基包（Modulith 红线）。
 *
 * <p>R1：Bean 一律注册；本 Bean 无 @Scheduled/@EventListener 触发点，全局 lazy-init
 * 下仅在首个调用方注入时实例化——门控未通过时 vectorStore 依赖会先触发
 * EmbeddingConfig 的防御性 tripwire；检索入口再行门控短路返回空集。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ArticleEmbeddingSearchService {

    private final VectorStore vectorStore;
    private final ClsArticleRepository articleRepository;
    private final EmbeddingProperties properties;
    private final EmbeddingGate gate;

    public List<ArticleEmbeddingHit> similaritySearch(String query) {
        return similaritySearch(query, properties.getSearch().getDefaultTopK(),
                properties.getSearch().getDefaultThreshold());
    }

    public List<ArticleEmbeddingHit> similaritySearch(String query, int topK, double threshold) {
        if (!gate.isAvailable()) {
            // 未启用时优雅降级：返回空集（P1 RAG 场景由调用方决定无上下文时的行为）
            log.debug("embedding unavailable, similarity search returns empty");
            return List.of();
        }
        if (query == null || query.isBlank() || topK <= 0) {
            return List.of();
        }
        SearchRequest request = SearchRequest.builder()
                .query(query)
                .topK(topK)
                .similarityThreshold(threshold)
                .build();
        List<Document> documents = vectorStore.similaritySearch(request);
        if (documents.isEmpty()) {
            return List.of();
        }

        // metadata 经 json 列序列化往返，articleId 反序列化为 Number 家族，统一 Number 兜底
        Map<Long, Document> byArticleId = new LinkedHashMap<>();
        for (Document document : documents) {
            Long articleId = extractArticleId(document);
            if (articleId != null) {
                byArticleId.putIfAbsent(articleId, document);
            }
        }
        Map<Long, ClsArticle> articles = articleRepository.findAllById(byArticleId.keySet()).stream()
                .collect(Collectors.toMap(ClsArticle::getId, Function.identity()));

        List<ArticleEmbeddingHit> hits = new ArrayList<>(documents.size());
        for (Document document : documents) {
            Long articleId = extractArticleId(document);
            ClsArticle article = articleId == null ? null : articles.get(articleId);
            if (article == null) {
                continue; // 状态行/向量行在但主表行缺失（脏数据防御）
            }
            hits.add(ArticleEmbeddingHit.builder()
                    .articleId(article.getId())
                    .title(article.getTitle())
                    .brief(article.getBrief())
                    .content(article.getContent())
                    .level(article.getLevel())
                    .ctime(article.getCtime())
                    .score(document.getScore())
                    .build());
        }
        return hits;
    }

    private static Long extractArticleId(Document document) {
        Object value = document.getMetadata().get("articleId");
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text) {
            try {
                return Long.parseLong(text);
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        return null;
    }
}
