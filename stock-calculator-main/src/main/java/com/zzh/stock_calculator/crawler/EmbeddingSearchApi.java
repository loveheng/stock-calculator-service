package com.zzh.stock_calculator.crawler;

import com.zzh.stock_calculator.crawler.embedding.config.EmbeddingGate;
import com.zzh.stock_calculator.crawler.embedding.dto.AnnouncementEmbeddingHit;
import com.zzh.stock_calculator.crawler.embedding.dto.ArticleEmbeddingHit;
import com.zzh.stock_calculator.crawler.embedding.service.AnnouncementEmbeddingSearchService;
import com.zzh.stock_calculator.crawler.embedding.service.ArticleEmbeddingSearchService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

/**
 * crawler 基包向量化检索门面（backend-implementation §1/§3：内部检索服务 javadoc 预留的
 * 「P1 场景接入时上提门面至 crawler 基包」；拍板 C12 基包开放 API/门面上提）。
 * search 域经此类型复用 vector_store 相似检索（cls 电报行 + announcement 公告行）与
 * 向量化可用性探针，不得触碰 crawler.embedding/service 子包（Modulith 红线）。
 * <p>内部检索服务经 {@link ObjectProvider} 惰性解析：门控关闭时调用方先行
 * {@link #isEmbeddingAvailable()} 短路，重 Bean（EmbeddingModel）不被实例化，
 * 维持 R1「未启用不实例化」语义；门控开启时实例化安全，tripwire 不触发。</p>
 */
@Service
@RequiredArgsConstructor
public class EmbeddingSearchApi {

    private final ObjectProvider<ArticleEmbeddingSearchService> searchServiceProvider;
    private final ObjectProvider<AnnouncementEmbeddingSearchService> announcementSearchServiceProvider;
    private final EmbeddingGate embeddingGate;

    /** 向量化运行期门控状态（各检索端点降级空集的判定依据；true 时实例化 EmbeddingModel 安全） */
    public boolean isEmbeddingAvailable() {
        return embeddingGate.isAvailable();
    }

    /**
     * cls_article 相似检索（相关度倒序；embedding 未启用时返回空集，调用方空态兜底）。
     * ctime 区间（秒级，闭区间）与排除名单下推 SQL——(metadata->>'ctime')::bigint 数值比较，
     * 杜绝「先召回后内存过滤」导致窄时段查不满/新数据挤不进 topK；区间传 null 即不限，
     * 排除名单传空集即不排除。共表红线：vector_store 与公告向量共表，来源区分在 SQL 内
     * 完成（cls 行有 articleId、公告行无，IS NOT NULL 自然排除），调用方无需再过滤。
     */
    public List<Hit> similaritySearch(String query, int topK, double threshold,
                                      Long ctimeFrom, Long ctimeTo, Collection<Long> excludeArticleIds) {
        if (!embeddingGate.isAvailable()) {
            return List.of();
        }
        ArticleEmbeddingSearchService searchService = searchServiceProvider.getIfAvailable();
        if (searchService == null) {
            return List.of();
        }
        return searchService.similaritySearch(query, topK, threshold,
                        ctimeFrom, ctimeTo, excludeArticleIds).stream()
                .map(EmbeddingSearchApi::toHit)
                .toList();
    }

    /**
     * announcement 公告向量行相似检索（相关度倒序；embedding 未启用时返回空集，调用方空态兜底）。
     * 共表判别 = metadata announcementId 键（公告行首版即有，B8 前存量行也可命中）；
     * secCode IN 恒下推（metadata 首版即有）；annDate 闭区间仅在存量回填完成后由调用方传入
     * （B8 前存量行缺 annDate，过渡期传 null 走调用方回查内存过滤）；排除名单供近窗两段式
     * 第二段补齐。主表元数据由调用方经 AnnouncementQueryApi 回查组装（本门面不跨域）。
     */
    public List<AnnouncementHit> announcementSimilaritySearch(String query, int topK, double threshold,
                                                              Collection<String> secCodes,
                                                              LocalDate dateFrom, LocalDate dateTo,
                                                              Collection<String> excludeAnnouncementIds) {
        if (!embeddingGate.isAvailable()) {
            return List.of();
        }
        AnnouncementEmbeddingSearchService searchService = announcementSearchServiceProvider.getIfAvailable();
        if (searchService == null) {
            return List.of();
        }
        return searchService.similaritySearch(query, topK, threshold,
                        secCodes, dateFrom, dateTo, excludeAnnouncementIds).stream()
                .map(hit -> new AnnouncementHit(hit.getAnnouncementId(), hit.getScore()))
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

    /** 公告向量命中项（基包公开载体，字段对齐内部 AnnouncementEmbeddingHit；仅 id + score） */
    public record AnnouncementHit(String announcementId, Double score) {
    }
}
