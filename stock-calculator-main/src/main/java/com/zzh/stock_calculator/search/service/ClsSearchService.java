package com.zzh.stock_calculator.search.service;

import com.zzh.stock_calculator.crawler.ClsArticleQueryApi;
import com.zzh.stock_calculator.crawler.EmbeddingSearchApi;
import com.zzh.stock_calculator.search.config.SearchProperties;
import com.zzh.stock_calculator.search.dto.SearchDtos.ClsItem;
import com.zzh.stock_calculator.search.dto.SearchDtos.ClsSearchResponse;
import com.zzh.stock_calculator.search.dto.SearchDtos.Mention;
import com.zzh.stock_calculator.search.util.SearchParamsValidator.DateRange;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 电报（CLS）检索（backend-implementation §3，api 文档 §3）。双路径路由：
 * <p>① 关键词精确路径（实体型/短查询）——「精确认领实体/关键词」的 query 不走向量
 * （2 字短名如「闻泰」嵌入区分度差，token 重叠噪声如「纳指ETF国泰」混入）：主判据
 * 纯数字代码或股票/题材字典包含命中（ClsArticleQueryApi.isEntityLikeQuery），兜底
 * 无空格 ≤ short-query-max-chars；content LIKE 走 pg_trgm GIN 索引，精确子串即门槛
 * （无相关性阈值），0 命中回落向量路径（字面无命中如「闻转债」时保语义召回）。</p>
 * <p>② 向量路径——EmbeddingSearchApi 下推 SQL，ctime 区间为 (metadata->>'ctime')::bigint
 * 数值硬过滤，共表排除（公告行无 articleId）同在 SQL 内。无 dateRange：近窗优先两段式
 * （第一段近 recent-window-days 天窗口、阈值仍在 SQL 内；不足 topK 第二段全量补齐，
 * 排除已选 + LIMIT=差额）；有 dateRange：单段硬下推闭区间，不做全量回落。</p>
 * <p>入选资格由路径门槛（精确匹配/相关性阈值）决定，输出按 ctime 倒序（最新在前）。
 * resultId = cls_article.id 文本（C4 口径，勿合成编号）；publishedAt = 东八区；
 * edition 恒 telegraph（Q3 终版定案：无早报/晚报）；summary = brief 优先，
 * 缺失截断 content ≤200 字（响应内即时截断，不落库）。
 * 向量降级语义在向量路径内部门控（embedding 未启用仅影响向量路径，短查询关键词路径不受影响）。</p>
 * <p>C1 红线：本服务日志只打条数/耗时，不打 query 明文。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ClsSearchService {

    private static final int SUMMARY_MAX_CHARS = 200;
    private static final ZoneId CN_ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter PUBLISHED_AT_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final EmbeddingSearchApi embeddingSearchApi;
    private final ClsArticleQueryApi clsArticleQueryApi;
    private final SearchProperties properties;

    /**
     * 分页语义（无限滑动）：两条路径都按「前缀加深 + 切片」翻页——召回深度
     * depth=(page+1)*pageSize，多取 1 条精确判 hasMore（末页不空拉），第 page 页输出
     * 列表切片 [page*pageSize, depth)。关键词路径天然确定序（ctime 倒序）；向量路径
     * top-K 加深保持前缀性质（page 0 结果恒为 page 1 前缀，两段式组合同步加深），
     * 页间新文章插入导致的轻微漂移为向量检索翻页的已知共性。
     */
    public ClsSearchResponse search(String query, DateRange dateRange, int pageSize, int page) {
        int depth = (page + 1) * pageSize;
        int fetchDepth = depth + 1;
        Long fromCtime = null;
        Long toCtime = null;
        if (dateRange != null) {
            fromCtime = dateRange.start().atStartOfDay(CN_ZONE).toEpochSecond();
            toCtime = dateRange.end().plusDays(1).atStartOfDay(CN_ZONE).toEpochSecond() - 1;
        }
        List<SearchHit> hits = List.of();
        if (isKeywordRoute(query)) {
            hits = clsArticleQueryApi.keywordSearch(query, fromCtime, toCtime, fetchDepth).stream()
                    .map(h -> new SearchHit(h.articleId(), h.title(), h.brief(), h.content(), h.ctime()))
                    .toList();
        }
        if (hits.isEmpty()) {
            hits = vectorSearch(query, fetchDepth, fromCtime, toCtime).stream()
                    .map(h -> new SearchHit(h.articleId(), h.title(), h.brief(), h.content(), h.ctime()))
                    .toList();
        }
        boolean hasMore = hits.size() > depth;
        if (hits.size() <= page * pageSize) {
            // 翻过头（数据不足本页起点）：空页收尾，hasMore 恒 false
            return ClsSearchResponse.builder().total(0).items(List.of()).hasMore(false).build();
        }
        List<SearchHit> pageHits = hits.subList(page * pageSize, Math.min(hits.size(), depth));

        Map<Long, List<ClsArticleQueryApi.Mention>> mentions =
                clsArticleQueryApi.mentionsByArticleIds(
                        pageHits.stream().map(SearchHit::articleId).toList());
        List<TimedItem> timed = new ArrayList<>(pageHits.size());
        for (SearchHit hit : pageHits) {
            if (hit.articleId() == null || hit.ctime() == null) {
                continue; // 脏数据防御：ctime 缺失会破坏 ctime 倒序输出
            }
            timed.add(new TimedItem(hit.ctime(), ClsItem.builder()
                    .resultId(String.valueOf(hit.articleId()))
                    .publishedAt(Instant.ofEpochSecond(hit.ctime()).atZone(CN_ZONE)
                            .format(PUBLISHED_AT_FORMAT))
                    .edition("telegraph") // 恒定口径（Q3 终版定案：无早报/晚报）
                    .title(hit.title())
                    .summary(summarize(hit.brief(), hit.content()))
                    .mentions(mentions.getOrDefault(hit.articleId(), List.of()).stream()
                            .map(m -> Mention.builder()
                                    .stockId(m.stockId())
                                    .stockName(m.stockName())
                                    .build())
                            .toList())
                    .build()));
        }
        List<ClsItem> items = timed.stream()
                .sorted(Comparator.comparingLong(TimedItem::ctime).reversed())
                .map(TimedItem::item)
                .toList();
        log.info("cls search done: vectorHits={}, page={}, pageSize={}, returned={}, hasMore={}, topK={}",
                hits.size(), page, pageSize, items.size(), hasMore, depth);
        return ClsSearchResponse.builder().total(items.size()).items(items).hasMore(hasMore).build();
    }

    /**
     * 路由判定：实体型（代码/字典命中，主判据）或短查询兜底（无空格 ≤ 配置长度），
     * 命中任一即走关键词精确路径。
     */
    private boolean isKeywordRoute(String query) {
        if (query == null) {
            return false;
        }
        if (clsArticleQueryApi.isEntityLikeQuery(query)) {
            return true;
        }
        String trimmed = query.trim();
        return !trimmed.contains(" ")
                && trimmed.length() <= properties.getRetrieval().getShortQueryMaxChars();
    }

    /**
     * 向量路径：embedding 未启用在门面内部门控（短查询关键词路径不受影响）；
     * dateRange 硬下推闭区间不做全量回落（用户明确圈定时段）；无 dateRange 近窗优先两段式，
     * 召回深度=fetchDepth（两段同步加深，保持跨页前缀性质）。
     */
    private List<EmbeddingSearchApi.Hit> vectorSearch(String query, int fetchDepth,
                                                      Long fromCtime, Long toCtime) {
        if (!embeddingSearchApi.isEmbeddingAvailable()) {
            log.info("cls search degraded: embedding unavailable, vector path returns empty");
            return List.of();
        }
        double threshold = properties.getRetrieval().getDefaultThreshold();
        if (fromCtime != null) {
            return embeddingSearchApi.similaritySearch(query, fetchDepth, threshold,
                    fromCtime, toCtime, List.of());
        }
        long windowFrom = Instant.now().getEpochSecond()
                - (long) properties.getRetrieval().getRecentWindowDays() * 86400;
        List<EmbeddingSearchApi.Hit> hits = new ArrayList<>(
                embeddingSearchApi.similaritySearch(query, fetchDepth, threshold, windowFrom, null, List.of()));
        if (hits.size() < fetchDepth && properties.getRetrieval().isFullCorpusFallback()) {
            // 第二段补齐：排除已选，LIMIT=差额；窗口内未入选的相关文章仍可回流
            List<Long> excludeIds = hits.stream().map(EmbeddingSearchApi.Hit::articleId).toList();
            hits.addAll(embeddingSearchApi.similaritySearch(query, fetchDepth - hits.size(), threshold,
                    null, null, excludeIds));
        }
        return hits;
    }

    /** 双路径命中统一载体（相关度分数不参与 cls 输出，仅关键词路径无分数，故不透传） */
    private record SearchHit(Long articleId, String title, String brief, String content, Long ctime) {
    }

    /** 入选序与输出序（ctime 倒序）解耦的配对载体：ClsItem 不含原始时间戳，需随行携带排序键 */
    private record TimedItem(long ctime, ClsItem item) {
    }

    /** summary：brief 优先；缺失/空白截断 content 前 200 字 */
    private static String summarize(String brief, String content) {
        if (brief != null && !brief.isBlank()) {
            return brief.trim();
        }
        String body = content == null ? "" : content.trim();
        return body.length() <= SUMMARY_MAX_CHARS ? body : body.substring(0, SUMMARY_MAX_CHARS);
    }
}
