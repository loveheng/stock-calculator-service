package com.zzh.stock_calculator.search.service;

import com.zzh.stock_calculator.announcement.AnnouncementQueryApi;
import com.zzh.stock_calculator.announcement.AnnouncementView;
import com.zzh.stock_calculator.crawler.EmbeddingSearchApi;
import com.zzh.stock_calculator.search.config.SearchProperties;
import com.zzh.stock_calculator.search.dto.SearchDtos.AnnouncementItem;
import com.zzh.stock_calculator.search.dto.SearchDtos.AnnouncementSearchResponse;
import com.zzh.stock_calculator.search.util.SearchParamsValidator.DateRange;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 公告摘要检索（backend-implementation §2，api 文档 §2）：
 * 向量召回 → metadata 提取 announcementId（cls 行自然排除）→ AnnouncementQueryApi 回查
 * → DONE/股票/日期/摘要过滤（保序）→ 截断 topK → DTO 组装。
 * <p>共表红线：vector_store 与 cls 向量共表，所有检索必须区分来源——过渡期靠
 * 「topK 放大 + 回查过滤」，metadata 回填（kind/annDate）完成后可切 filterExpression 下推
 * （{@code search.retrieval.kind-filter-enabled=true}）。</p>
 * <p>C1 红线：本服务日志只打条数/耗时，不打 query/stockCodes 明文。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AnnouncementSearchService {

    /** 过渡期（metadata 回填完成前）topK 放大倍数（backend-implementation §2 步骤 2：3~5 倍，取 4） */
    private static final int LEGACY_EXPAND_FACTOR = 4;

    private final AnnouncementQueryApi announcementQueryApi;
    private final EmbeddingSearchApi embeddingSearchApi;
    private final ObjectProvider<VectorStore> vectorStoreProvider;
    private final SearchProperties properties;

    public AnnouncementSearchResponse search(String query, List<String> stockCodes,
                                             DateRange dateRange, int topK) {
        if (!embeddingSearchApi.isEmbeddingAvailable()) {
            log.info("announcement search degraded: embedding unavailable, returns empty");
            return empty();
        }
        VectorStore vectorStore = vectorStoreProvider.getIfAvailable();
        if (vectorStore == null) {
            log.info("announcement search degraded: vector store absent, returns empty");
            return empty();
        }

        boolean kindFilter = properties.getRetrieval().isKindFilterEnabled();
        int retrievalTopK = kindFilter ? topK : topK * LEGACY_EXPAND_FACTOR;
        SearchRequest.Builder builder = SearchRequest.builder()
                .query(query)
                .topK(retrievalTopK)
                .similarityThreshold(properties.getRetrieval().getDefaultThreshold());
        Filter.Expression filter = buildFilterExpression(stockCodes, dateRange, kindFilter);
        if (filter != null) {
            builder.filterExpression(filter);
        }
        List<Document> documents = vectorStore.similaritySearch(builder.build());
        if (documents.isEmpty()) {
            return empty();
        }

        // 保序收集向量命中中的 announcementId（cls 行无此 metadata，提取时自然排除）
        Map<String, AnnouncementView> byAnnouncementId = new LinkedHashMap<>();
        for (Document document : documents) {
            String announcementId = strMeta(document, "announcementId");
            if (announcementId != null) {
                byAnnouncementId.putIfAbsent(announcementId, null);
            }
        }
        announcementQueryApi.findAllByAnnouncementIdIn(byAnnouncementId.keySet())
                .forEach(view -> byAnnouncementId.put(view.announcementId(), view));

        Set<String> secCodeSet = stockCodes == null ? null : new HashSet<>(stockCodes);
        List<AnnouncementItem> items = new ArrayList<>(Math.min(topK, documents.size()));
        for (Document document : documents) {
            if (items.size() >= topK) {
                break; // 相关度倒序，截断 topK
            }
            AnnouncementView view = byAnnouncementId.get(strMeta(document, "announcementId"));
            if (view == null || !"DONE".equals(view.status())) {
                continue; // 向量行在但主表行缺失（脏数据防御）/未完成蒸馏
            }
            if (secCodeSet != null && !secCodeSet.contains(view.secCode())) {
                continue; // 回查二次校验（与 filterExpression 双保险）
            }
            if (dateRange != null && (view.seDate() == null
                    || view.seDate().isBefore(dateRange.start())
                    || view.seDate().isAfter(dateRange.end()))) {
                continue;
            }
            if (view.summary() == null || view.summary().isBlank()) {
                continue; // D5 不存正文，无摘要即无降级来源，直接不返回
            }
            items.add(AnnouncementItem.builder()
                    .resultId(view.announcementId())
                    .stockId(view.secCode())
                    .stockName(view.secName() == null ? "" : view.secName())
                    .annDate(view.seDate() == null ? null : view.seDate().toString())
                    .title(view.title())
                    .summary(view.summary().trim())
                    .sourceUrl(view.sourceUrl())
                    .build());
        }
        log.info("announcement search done: vectorHits={}, returned={}, topK={}",
                documents.size(), items.size(), topK);
        return AnnouncementSearchResponse.builder().total(items.size()).items(items).build();
    }

    /**
     * filterExpression（Spring AI 2.0.1 FilterExpressionBuilder DSL，m2 sources 实证）：
     * kindFilter 开（回填完成）：kind=='announcement' [+ secCode IN] [+ annDate 闭区间] 全部下推 SQL；
     * kindFilter 关（过渡期）：仅 [+ secCode IN]（cls 行无 secCode 键，SQL 下推即排除），
     * 来源与时间过滤靠回查兜底。annDate 为 ISO 日期文本，字典序即时间序。
     */
    private Filter.Expression buildFilterExpression(List<String> stockCodes,
                                                    DateRange dateRange, boolean kindFilter) {
        FilterExpressionBuilder b = new FilterExpressionBuilder();
        FilterExpressionBuilder.Op op = null;
        if (kindFilter) {
            op = b.eq("kind", "announcement");
        }
        if (stockCodes != null && !stockCodes.isEmpty()) {
            FilterExpressionBuilder.Op in = b.in("secCode", stockCodes.toArray(new String[0]));
            op = op == null ? in : b.and(op, in);
        }
        if (kindFilter && dateRange != null) {
            FilterExpressionBuilder.Op range = b.and(
                    b.gte("annDate", dateRange.start().toString()),
                    b.lte("annDate", dateRange.end().toString()));
            op = op == null ? range : b.and(op, range);
        }
        return op == null ? null : op.build();
    }

    private static String strMeta(Document document, String key) {
        Object value = document.getMetadata().get(key);
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        return text.isBlank() ? null : text;
    }

    private static AnnouncementSearchResponse empty() {
        return AnnouncementSearchResponse.builder().total(0).items(List.of()).build();
    }
}
