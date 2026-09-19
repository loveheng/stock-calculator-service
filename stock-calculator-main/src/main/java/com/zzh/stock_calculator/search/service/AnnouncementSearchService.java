package com.zzh.stock_calculator.search.service;

import com.zzh.stock_calculator.announcement.AnnouncementQueryApi;
import com.zzh.stock_calculator.announcement.AnnouncementView;
import com.zzh.stock_calculator.crawler.ClsArticleQueryApi;
import com.zzh.stock_calculator.crawler.EmbeddingSearchApi;
import com.zzh.stock_calculator.search.config.SearchProperties;
import com.zzh.stock_calculator.search.dto.SearchDtos.AnnouncementItem;
import com.zzh.stock_calculator.search.dto.SearchDtos.AnnouncementSearchResponse;
import com.zzh.stock_calculator.search.util.SearchParamsValidator.DateRange;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 公告摘要检索（backend-implementation §2，api 文档 §2）。与电报（ClsSearchService）同款
 * 双路径路由 + 分页（无限滑动）：
 * <p>① 关键词精确路径（实体型/短查询）——实体认领/短关键词 query 不走向量（2 字短名嵌入
 * 区分度差）：主判据复用 {@code ClsArticleQueryApi.isEntityLikeQuery}（代码/股票/题材字典
 * 命中），兜底无空格 ≤ short-query-max-chars；AnnouncementQueryApi.keywordSearch 对
 * title/summary/secName/secCode LIKE（pg_trgm GIN 索引），DONE+摘要非空门槛在 SQL 内，
 * 0 命中回落向量路径。</p>
 * <p>② 向量路径——EmbeddingSearchApi.announcementSimilaritySearch 下推 SQL
 * （announcementId 键判别共表来源 + secCode IN 恒下推），命中后经 AnnouncementQueryApi
 * 批量回查组装（DONE/摘要非空/股票/日期过滤为主表口径的脏数据防御）。dateRange 与
 * annDate 下推由 search.retrieval.kind-filter-enabled 门控：true（回填完成）＝区间下推 +
 * 无 dateRange 时近窗优先两段式（近 recent-window-days 窗口 → 全量补齐，排除已选 +
 * LIMIT=差额，full-corpus-fallback 门控）；false（过渡期，B8 前存量行缺 annDate）＝单段
 * 全量召回，召回深度 ×4 补偿内存过滤损耗，股票/日期靠回查内存过滤。</p>
 * <p>分页语义（无限滑动）：depth=(page+1)*pageSize，多取 1 条精确判 hasMore（末页不空拉），
 * 第 page 页输出列表切片 [page*pageSize, depth)；两路径统一「前缀加深 + 切片」，向量
 * top-K 加深保持前缀性质（两段式组合同步加深），翻超界空页收尾。</p>
 * <p>入选资格由路径门槛（精确匹配/相关性阈值/DONE+摘要非空）决定，输出按公告日倒序
 * （相关度只决定入选；同日按主表 id 倒序，与关键词路径 ORDER BY 对齐）。
 * resultId = CNINFO announcementId（C4 口径）；无摘要行不返回（D5：无降级来源）。
 * 共表红线：来源判别已下推 SQL，调用方无需再过滤。</p>
 * <p>C1 红线：本服务日志只打条数/耗时，不打 query/stockCodes 明文。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AnnouncementSearchService {

    /** 过渡期（metadata 回填完成前）召回深度放大倍数（backend-implementation §2 步骤 2：3~5 倍，取 4） */
    private static final int LEGACY_EXPAND_FACTOR = 4;
    private static final ZoneId CN_ZONE = ZoneId.of("Asia/Shanghai");

    private final AnnouncementQueryApi announcementQueryApi;
    private final EmbeddingSearchApi embeddingSearchApi;
    private final ClsArticleQueryApi clsArticleQueryApi;
    private final SearchProperties properties;

    /**
     * 分页语义（无限滑动）：两条路径都按「前缀加深 + 切片」翻页——召回深度
     * depth=(page+1)*pageSize，多取 1 条精确判 hasMore（末页不空拉），第 page 页输出
     * 列表切片 [page*pageSize, depth)。关键词路径天然确定序（公告日倒序）；向量路径
     * top-K 加深保持前缀性质（page 0 结果恒为 page 1 前缀，两段式组合同步加深），
     * 新公告入库导致的轻微漂移为向量检索翻页的已知共性。
     */
    public AnnouncementSearchResponse search(String query, List<String> stockCodes,
                                             DateRange dateRange, int pageSize, int page) {
        int depth = (page + 1) * pageSize;
        int fetchDepth = depth + 1;
        List<AnnouncementView> hits = List.of();
        if (isKeywordRoute(query)) {
            hits = announcementQueryApi.keywordSearch(query, stockCodes,
                    dateRange == null ? null : dateRange.start(),
                    dateRange == null ? null : dateRange.end(),
                    fetchDepth);
        }
        if (hits.isEmpty()) {
            hits = vectorSearch(query, stockCodes, dateRange, fetchDepth);
        }
        boolean hasMore = hits.size() > depth;
        if (hits.size() <= page * pageSize) {
            // 翻过头（数据不足本页起点）：空页收尾，hasMore 恒 false
            return AnnouncementSearchResponse.builder().total(0).items(List.of()).hasMore(false).build();
        }
        List<AnnouncementView> pageHits = hits.subList(page * pageSize, Math.min(hits.size(), depth));

        List<AnnouncementItem> items = pageHits.stream()
                .map(this::toItem)
                .sorted(Comparator
                        .comparing(TimedItem::seDate, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(TimedItem::id, Comparator.reverseOrder()))
                .map(TimedItem::item)
                .toList();
        log.info("announcement search done: hits={}, page={}, pageSize={}, returned={}, hasMore={}, depth={}",
                hits.size(), page, pageSize, items.size(), hasMore, depth);
        return AnnouncementSearchResponse.builder().total(items.size()).items(items).hasMore(hasMore).build();
    }

    /**
     * 路由判定：实体型（代码/字典命中，主判据，与电报共用同款判定）或短查询兜底
     * （无空格 ≤ 配置长度），命中任一即走关键词精确路径。
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
     * dateRange/近窗的 annDate 下推仅回填完成后启用（过渡期单段全量召回 + 深度放大，
     * 股票/日期过滤靠回查内存完成）。
     */
    private List<AnnouncementView> vectorSearch(String query, List<String> stockCodes,
                                                DateRange dateRange, int fetchDepth) {
        if (!embeddingSearchApi.isEmbeddingAvailable()) {
            log.info("announcement search degraded: embedding unavailable, vector path returns empty");
            return List.of();
        }
        boolean metadataFilter = properties.getRetrieval().isKindFilterEnabled();
        int retrievalDepth = metadataFilter ? fetchDepth : fetchDepth * LEGACY_EXPAND_FACTOR;
        double threshold = properties.getRetrieval().getDefaultThreshold();
        LocalDate from = metadataFilter && dateRange != null ? dateRange.start() : null;
        LocalDate to = metadataFilter && dateRange != null ? dateRange.end() : null;
        List<EmbeddingSearchApi.AnnouncementHit> vectorHits;
        if (metadataFilter && from == null && to == null) {
            vectorHits = twoPhaseSearch(query, stockCodes, retrievalDepth, threshold);
        } else {
            vectorHits = embeddingSearchApi.announcementSimilaritySearch(query, retrievalDepth, threshold,
                    stockCodes, from, to, List.of());
        }
        return resolveAndFilter(vectorHits, stockCodes, dateRange);
    }

    /**
     * 近窗优先两段式（cls 同款，仅 metadata 回填完成后启用）：第一段近 recent-window-days
     * 窗口（annDate 下推），不足召回深度第二段全量补齐（排除已选，LIMIT=差额）；
     * full-corpus-fallback 关闭则近窗不足即返回不足额。
     */
    private List<EmbeddingSearchApi.AnnouncementHit> twoPhaseSearch(String query, List<String> stockCodes,
                                                                    int retrievalDepth, double threshold) {
        LocalDate windowFrom = LocalDate.now(CN_ZONE)
                .minusDays(properties.getRetrieval().getRecentWindowDays());
        List<EmbeddingSearchApi.AnnouncementHit> hits = new ArrayList<>(
                embeddingSearchApi.announcementSimilaritySearch(query, retrievalDepth, threshold,
                        stockCodes, windowFrom, null, List.of()));
        if (hits.size() < retrievalDepth && properties.getRetrieval().isFullCorpusFallback()) {
            List<String> excludeIds = hits.stream()
                    .map(EmbeddingSearchApi.AnnouncementHit::announcementId).toList();
            hits.addAll(embeddingSearchApi.announcementSimilaritySearch(query,
                    retrievalDepth - hits.size(), threshold, stockCodes, null, null, excludeIds));
        }
        return hits;
    }

    /**
     * 批量回查主表组装 + 门槛过滤（保序）：向量行在但主表行缺失/未完成蒸馏（脏数据防御）、
     * 摘要空白（D5：无摘要即无降级来源）、股票/日期不符（主表口径二次校验，与 SQL 下推
     * 双保险——过渡期两者只有内存过滤这一道）均跳过不占名额。
     */
    private List<AnnouncementView> resolveAndFilter(List<EmbeddingSearchApi.AnnouncementHit> vectorHits,
                                                    List<String> stockCodes, DateRange dateRange) {
        if (vectorHits.isEmpty()) {
            return List.of();
        }
        Set<String> wantedIds = new HashSet<>();
        vectorHits.forEach(hit -> wantedIds.add(hit.announcementId()));
        Map<String, AnnouncementView> byAnnouncementId = new LinkedHashMap<>();
        announcementQueryApi.findAllByAnnouncementIdIn(wantedIds)
                .forEach(view -> byAnnouncementId.put(view.announcementId(), view));
        Set<String> secCodeSet = stockCodes == null ? null : new HashSet<>(stockCodes);
        List<AnnouncementView> result = new ArrayList<>(vectorHits.size());
        for (EmbeddingSearchApi.AnnouncementHit hit : vectorHits) {
            AnnouncementView view = byAnnouncementId.get(hit.announcementId());
            if (view == null || !"DONE".equals(view.status())) {
                continue; // 向量行在但主表行缺失（脏数据防御）/未完成蒸馏
            }
            if (secCodeSet != null && !secCodeSet.contains(view.secCode())) {
                continue; // 回查二次校验（与 secCode 下推双保险）
            }
            if (dateRange != null && (view.seDate() == null
                    || view.seDate().isBefore(dateRange.start())
                    || view.seDate().isAfter(dateRange.end()))) {
                continue;
            }
            if (view.summary() == null || view.summary().isBlank()) {
                continue; // D5 不存正文，无摘要即无降级来源，直接不返回
            }
            result.add(view);
        }
        return result;
    }

    /** DTO 组装（配对载体随行携带排序键：入选序=相关度，输出序=公告日倒序） */
    private TimedItem toItem(AnnouncementView view) {
        return new TimedItem(view.seDate(), view.id(), AnnouncementItem.builder()
                .resultId(view.announcementId())
                .stockId(view.secCode())
                .stockName(view.secName() == null ? "" : view.secName())
                .annDate(view.seDate() == null ? null : view.seDate().toString())
                .title(view.title())
                .summary(view.summary() == null ? "" : view.summary().trim())
                .sourceUrl(view.sourceUrl())
                .build());
    }

    /** 入选序与输出序（公告日倒序，同日按主表 id 倒序）解耦的配对载体 */
    private record TimedItem(LocalDate seDate, Long id, AnnouncementItem item) {
    }
}
