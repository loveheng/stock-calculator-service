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
import java.util.List;
import java.util.Map;

/**
 * 电报（CLS）检索（backend-implementation §3，api 文档 §3）：
 * EmbeddingSearchApi 相似召回 → dateRange 按 ctime 闭区间过滤（保序）→ 截断 topK
 * → mention 批量组装。共表红线：cls 行 metadata 无 kind，公告行无 articleId、
 * 在门面回查 ClsArticle 时被自然排除，无需额外来源过滤。
 * <p>过渡期口径（与公告检索同款）：召回量 = topK×4 放大，来源与时间过滤在内存完成；
 * ctime 数值 metadata 经 JSONB 文本比较有坑，dateRange 不下推 SQL。
 * resultId = cls_article.id 文本（C4 口径，勿合成编号）；publishedAt = 东八区；
 * edition 恒 telegraph（Q3 终版定案：无早报/晚报）；summary = brief 优先，
 * 缺失截断 content ≤200 字（响应内即时截断，不落库）。</p>
 * <p>C1 红线：本服务日志只打条数/耗时，不打 query 明文。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ClsSearchService {

    /** 过渡期 topK 放大倍数（公告向量共表挤占召回，backend-implementation §3 步骤 2） */
    private static final int RECALL_EXPAND_FACTOR = 4;
    private static final int SUMMARY_MAX_CHARS = 200;
    private static final ZoneId CN_ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter PUBLISHED_AT_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final EmbeddingSearchApi embeddingSearchApi;
    private final ClsArticleQueryApi clsArticleQueryApi;
    private final SearchProperties properties;

    public ClsSearchResponse search(String query, DateRange dateRange, int topK) {
        if (!embeddingSearchApi.isEmbeddingAvailable()) {
            log.info("cls search degraded: embedding unavailable, returns empty");
            return empty();
        }
        List<EmbeddingSearchApi.Hit> hits = embeddingSearchApi.similaritySearch(query,
                topK * RECALL_EXPAND_FACTOR, properties.getRetrieval().getDefaultThreshold());
        if (hits.isEmpty()) {
            return empty();
        }
        Map<Long, List<ClsArticleQueryApi.Mention>> mentions =
                clsArticleQueryApi.mentionsByArticleIds(
                        hits.stream().map(EmbeddingSearchApi.Hit::articleId).toList());
        Long startCtime = dateRange == null ? null
                : dateRange.start().atStartOfDay(CN_ZONE).toEpochSecond();
        Long endCtime = dateRange == null ? null
                : dateRange.end().plusDays(1).atStartOfDay(CN_ZONE).toEpochSecond() - 1;

        List<ClsItem> items = new ArrayList<>(Math.min(topK, hits.size()));
        for (EmbeddingSearchApi.Hit hit : hits) {
            if (items.size() >= topK) {
                break; // 相关度倒序，截断 topK
            }
            if (hit.articleId() == null || hit.ctime() == null) {
                continue; // 向量行在但主表行缺失（脏数据防御）
            }
            if (startCtime != null && (hit.ctime() < startCtime || hit.ctime() > endCtime)) {
                continue;
            }
            items.add(ClsItem.builder()
                    .resultId(String.valueOf(hit.articleId()))
                    .publishedAt(Instant.ofEpochSecond(hit.ctime()).atZone(CN_ZONE)
                            .format(PUBLISHED_AT_FORMAT))
                    .edition("telegraph") // 恒定口径（Q3 终版定案：无早报/晚报）
                    .title(hit.title())
                    .summary(summarize(hit))
                    .mentions(mentions.getOrDefault(hit.articleId(), List.of()).stream()
                            .map(m -> Mention.builder()
                                    .stockId(m.stockId())
                                    .stockName(m.stockName())
                                    .build())
                            .toList())
                    .build());
        }
        log.info("cls search done: vectorHits={}, returned={}, topK={}",
                hits.size(), items.size(), topK);
        return ClsSearchResponse.builder().total(items.size()).items(items).build();
    }

    /** summary：brief 优先；缺失/空白截断 content 前 200 字 */
    private static String summarize(EmbeddingSearchApi.Hit hit) {
        String brief = hit.brief();
        if (brief != null && !brief.isBlank()) {
            return brief.trim();
        }
        String content = hit.content() == null ? "" : hit.content().trim();
        return content.length() <= SUMMARY_MAX_CHARS ? content
                : content.substring(0, SUMMARY_MAX_CHARS);
    }

    private static ClsSearchResponse empty() {
        return ClsSearchResponse.builder().total(0).items(List.of()).build();
    }
}
