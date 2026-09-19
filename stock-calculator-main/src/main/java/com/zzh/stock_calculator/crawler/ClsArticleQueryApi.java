package com.zzh.stock_calculator.crawler;

import com.zzh.stock_calculator.crawler.entity.ClsArticle;
import com.zzh.stock_calculator.crawler.entity.ClsArticleStock;
import com.zzh.stock_calculator.crawler.repository.ClsArticleRepository;
import com.zzh.stock_calculator.crawler.repository.ClsArticleStockRepository;
import com.zzh.stock_calculator.crawler.repository.ClsSubjectRepository;
import com.zzh.stock_calculator.crawler.repository.StockRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * crawler 基包电报查询 API（backend-implementation §1；拍板 C12）：
 * cls_article_stock 关联口径查询 + 短查询实体判定/关键词精确检索，
 * 供 search 域组装 mention 与路由短查询路径。entity 是内部类型，
 * 返回值一律用基包 record 载体（Modulith 红线）。
 */
@Service
@RequiredArgsConstructor
public class ClsArticleQueryApi {

    private final ClsArticleStockRepository stockLinkRepository;
    private final StockRepository stockRepository;
    private final ClsSubjectRepository clsSubjectRepository;
    private final ClsArticleRepository articleRepository;

    /** articleId → 提及股票列表（按关联写入序；无提及 = 空列表；字典未收录时 name 兜底空串） */
    public Map<Long, List<Mention>> mentionsByArticleIds(Collection<Long> articleIds) {
        if (articleIds == null || articleIds.isEmpty()) {
            return Map.of();
        }
        List<ClsArticleStock> links = stockLinkRepository.findByArticleIdIn(articleIds);
        if (links.isEmpty()) {
            return Map.of();
        }
        Set<String> codes = new HashSet<>();
        for (ClsArticleStock link : links) {
            codes.add(link.getStockId());
        }
        Map<String, String> nameByCode = new HashMap<>();
        stockRepository.findAllById(codes).forEach(
                stock -> nameByCode.put(stock.getStockId(), stock.getName()));
        Map<Long, List<Mention>> result = new LinkedHashMap<>();
        for (ClsArticleStock link : links) {
            result.computeIfAbsent(link.getArticleId(), key -> new ArrayList<>())
                    .add(new Mention(link.getStockId(),
                            nameByCode.getOrDefault(link.getStockId(), "")));
        }
        return result;
    }

    /** 指定股票自 sinceCtime（秒）起被电报提及的文章数（P2 clsMention 预留） */
    public long countByStockCodeSince(String stockCode, long sinceCtime) {
        if (stockCode == null || stockCode.isBlank()) {
            return 0;
        }
        return stockLinkRepository.countDistinctArticleIdByStockIdSince(stockCode, sinceCtime);
    }

    /**
     * 实体型查询判定（search 短查询路由主判据，命中即应走关键词精确路径而非向量）：
     * ① 纯数字——股票/ETF 代码，数字嵌入无语义，向量必失准；
     * ② query 被股票 name/old_name 或题材名包含（「闻泰」⊂「闻泰科技」）——用户在精确认领实体。
     * 反向不判（query 含字典名但更长，如「闻泰科技爆雷」）：长上下文嵌入区分度足够，仍走向量。
     */
    public boolean isEntityLikeQuery(String query) {
        if (query == null || query.isBlank()) {
            return false;
        }
        String trimmed = query.trim();
        if (trimmed.chars().allMatch(Character::isDigit)) {
            return true;
        }
        return !stockRepository.findByNameContaining(trimmed).isEmpty()
                || !stockRepository.findByOldNameContaining(trimmed).isEmpty()
                || !clsSubjectRepository.findBySubjectNameContaining(trimmed).isEmpty();
    }

    /**
     * 关键词精确检索（短查询路径）：content 子串匹配（LIKE，走 pg_trgm GIN 索引），
     * ctime 区间可选（dateRange 存在时成对传入，否则双 null 全时段），ctime 倒序取前 limit 条。
     * 语义=「精确匹配用户所输」，无相关性阈值；调用方 0 命中时自行回落向量路径。
     */
    public List<ArticleHit> keywordSearch(String keyword, Long fromCtime, Long toCtime, int limit) {
        if (keyword == null || keyword.isBlank() || limit <= 0) {
            return List.of();
        }
        return articleRepository.searchByContentKeyword(keyword.trim(), fromCtime, toCtime,
                        Limit.of(limit)).stream()
                .map(ClsArticleQueryApi::toArticleHit)
                .toList();
    }

    /**
     * 最新《新闻联播》要闻汇编候选（news-kg 发布器扫描源，docs/ai-pipeline/cls-news-kg.md §7）：
     * title 含关键字（LIKE %kw%）按 ctime 倒序取前 limit 条；正文随载体直入任务 payload（data 不回源）。
     */
    public List<DigestArticle> latestDigestArticles(String titleKeyword, int limit) {
        if (titleKeyword == null || titleKeyword.isBlank() || limit <= 0) {
            return List.of();
        }
        return articleRepository
                .findByTitleContainingOrderByCtimeDesc(titleKeyword.trim(), Limit.of(limit))
                .stream()
                .map(a -> new DigestArticle(a.getId(), a.getTitle(), a.getCtime(), a.getContent()))
                .toList();
    }

    /**
     * 最旧《新闻联播》要闻汇编候选（news-kg 历史回填扫描源，cls-news-kg.md §7 二期）：
     * 与 {@link #latestDigestArticles} 同口径镜像，ctime 正序取前 limit 条——
     * 回填「最旧优先」分批补录，窗口随终态累积自然前滑。
     */
    public List<DigestArticle> oldestDigestArticles(String titleKeyword, int limit) {
        if (titleKeyword == null || titleKeyword.isBlank() || limit <= 0) {
            return List.of();
        }
        return articleRepository
                .findByTitleContainingOrderByCtimeAsc(titleKeyword.trim(), Limit.of(limit))
                .stream()
                .map(a -> new DigestArticle(a.getId(), a.getTitle(), a.getCtime(), a.getContent()))
                .toList();
    }

    /**
     * 按主键批量取电报头（news-kg 时间轴日头：articleId → 标题/ctime，仅头不取正文）；
     * 未知 id 静默跳过（图谱行与源表弱一致，源站撤稿时日头缺省为空串）。
     */
    public Map<Long, ArticleHead> articleHeadsByIds(Collection<Long> articleIds) {
        if (articleIds == null || articleIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, ArticleHead> result = new LinkedHashMap<>();
        for (ClsArticle a : articleRepository.findByIdIn(articleIds)) {
            result.put(a.getId(), new ArticleHead(a.getId(), a.getTitle(), a.getCtime()));
        }
        return result;
    }

    private static ArticleHit toArticleHit(ClsArticle article) {
        return new ArticleHit(article.getId(), article.getTitle(), article.getBrief(),
                article.getContent(), article.getLevel(), article.getCtime());
    }

    /** 电报提及股票（基包公开载体；stockName 为字典快照名，未收录 = 空串） */
    public record Mention(String stockId, String stockName) {
    }

    /** 关键词精确检索命中项（基包公开载体，字段对齐 EmbeddingSearchApi.Hit，无相关性分数） */
    public record ArticleHit(Long articleId, String title, String brief, String content,
                             String level, Long ctime) {
    }

    /** 汇编候选载体（news-kg；content 为电报正文全文，level 恒 B 不需携带） */
    public record DigestArticle(Long articleId, String title, Long ctime, String content) {
    }

    /** 电报头载体（news-kg 时间轴日头；仅标题与发布时间，不含正文） */
    public record ArticleHead(Long articleId, String title, Long ctime) {
    }
}
