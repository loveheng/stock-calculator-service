package com.zzh.stock_calculator.crawler;

import com.zzh.stock_calculator.crawler.embedding.config.EmbeddingGate;
import com.zzh.stock_calculator.crawler.embedding.dto.ArticleEmbeddingHit;
import com.zzh.stock_calculator.crawler.embedding.service.ArticleEmbeddingSearchService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * crawler 基包向量化检索门面（backend-implementation §1/§3：内部检索服务 javadoc 预留的
 * 「P1 场景接入时上提门面至 crawler 基包」；拍板 C12 基包开放 API/门面上提）。
 * search 域经此类型复用 vector_store 相似检索与向量化可用性探针，
 * 不得触碰 crawler.embedding/service 子包（Modulith 红线）。
 * <p>内部检索服务经 {@link ObjectProvider} 惰性解析：门控关闭时调用方先行
 * {@link #isEmbeddingAvailable()} 短路，重 Bean（VectorStore/EmbeddingModel）不被实例化，
 * 维持 R1「未启用不实例化」语义；门控开启时实例化安全，tripwire 不触发。</p>
 */
@Service
@RequiredArgsConstructor
public class EmbeddingSearchApi {

    private final ObjectProvider<ArticleEmbeddingSearchService> searchServiceProvider;
    private final EmbeddingGate embeddingGate;

    /** 向量化运行期门控状态（各检索端点降级空集的判定依据；true 时实例化 VectorStore 安全） */
    public boolean isEmbeddingAvailable() {
        return embeddingGate.isAvailable();
    }

    /**
     * cls_article 相似检索（相关度倒序；embedding 未启用时返回空集，调用方空态兜底）。
     * 共表红线：vector_store 与公告向量共表，调用方必须自行保证来源区分
     * （cls 行 metadata 无 announcementId/kind；公告行无 articleId，在本门面内被自然排除）。
     */
    public List<Hit> similaritySearch(String query, int topK, double threshold) {
        if (!embeddingGate.isAvailable()) {
            return List.of();
        }
        ArticleEmbeddingSearchService searchService = searchServiceProvider.getIfAvailable();
        if (searchService == null) {
            return List.of();
        }
        return searchService.similaritySearch(query, topK, threshold).stream()
                .map(EmbeddingSearchApi::toHit)
                .toList();
    }

    private static Hit toHit(ArticleEmbeddingHit hit) {
        return new Hit(hit.getArticleId(), hit.getTitle(), hit.getBrief(), hit.getContent(),
                hit.getLevel(), hit.getCtime(), hit.getScore());
    }

    /** 检索命中项（基包公开载体，字段对齐内部 ArticleEmbeddingHit；score = cosine 相似度 [0,1]） */
    public record Hit(Long articleId, String title, String brief, String content, String level,
                      Long ctime, Double score) {
    }
}
