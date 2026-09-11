package com.zzh.stock_calculator.data.cls;

import com.zzh.stockcalc.contract.message.ClsArticleDto;
import com.zzh.stockcalc.contract.message.ClsArticlePayload;
import com.zzh.stockcalc.contract.message.ClsStockDict;
import com.zzh.stockcalc.contract.message.ClsStockLink;
import com.zzh.stockcalc.contract.message.ClsSubjectDict;
import com.zzh.stockcalc.contract.message.ClsSubjectLink;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 电报单条解析（自 main 模块 ClsDayTaskHelp 的解析段移植）：产出契约 DTO 而非 JPA 实体。
 * 字典与关联分开解析（cls-article-patterns §6.1）；id 缺失返回 null 由调用方跳过。
 */
public final class ClsArticleParser {

    private ClsArticleParser() {}

    public static ClsArticlePayload parse(Map<String, Object> item) {
        Long articleId = ClsValueUtil.toLongOrNull(item.get("id"));
        if (articleId == null) {
            return null;
        }

        ClsArticleDto article = ClsArticleDto.builder()
                .id(articleId)
                .type(ClsValueUtil.toInt(item.get("type"), -1))
                .title(ClsValueUtil.asStr(item.get("title")))
                .brief(ClsValueUtil.asStr(item.get("brief")))
                .content(ClsValueUtil.asStr(item.get("content")))
                .ctime(ClsValueUtil.toLong(item.get("ctime"), 0L))
                .author(ClsValueUtil.asStr(item.getOrDefault("author", "")))
                .level(ClsValueUtil.asStr(item.getOrDefault("level", "C")))
                .images(ClsValueUtil.parseJsonStrList(item.get("images")))
                .audioUrl(ClsValueUtil.parseJsonStrList(item.get("audio_url")))
                .build();

        Object subjectRaw = item.get("subjects");
        Object stockRaw = item.get("stock_list");

        return ClsArticlePayload.builder()
                .article(article)
                .subjectDicts(parseSubjectDicts(subjectRaw))
                .subjectLinks(parseSubjectLinks(subjectRaw, articleId))
                .stockDicts(parseStockDicts(stockRaw))
                .stockLinks(parseStockLinks(stockRaw, articleId))
                .build();
    }

    // ========== 题材：字典 + 关联 分开解析 ==========

    private static List<ClsSubjectDict> parseSubjectDicts(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<ClsSubjectDict> result = new ArrayList<>();
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m)) continue;
            Map<String, Object> map = ClsValueUtil.coerceMap(m);
            result.add(ClsSubjectDict.builder()
                    .subjectId(ClsValueUtil.toLong(map.get("subject_id"), 0L))
                    .subjectName(ClsValueUtil.asStr(map.get("subject_name")))
                    .plateId(ClsValueUtil.toLongOrNull(map.get("plate_id")))
                    .channel(ClsValueUtil.asStr(map.get("channel")))
                    .build());
        }
        return result;
    }

    private static List<ClsSubjectLink> parseSubjectLinks(Object raw, long articleId) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<ClsSubjectLink> result = new ArrayList<>();
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m)) continue;
            Map<String, Object> map = ClsValueUtil.coerceMap(m);
            result.add(ClsSubjectLink.builder()
                    .articleId(articleId)
                    .subjectId(ClsValueUtil.toLong(map.get("subject_id"), 0L))
                    .build());
        }
        return result;
    }

    // ========== 股票：字典 + 关联 分开解析 ==========

    private static List<ClsStockDict> parseStockDicts(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<ClsStockDict> result = new ArrayList<>();
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m)) continue;
            Map<String, Object> map = ClsValueUtil.coerceMap(m);
            String stockId = ClsValueUtil.asStr(map.get("StockID"));
            if (stockId == null || stockId.isBlank()) continue;
            String name = ClsValueUtil.asStr(map.get("name"));
            result.add(ClsStockDict.builder()
                    .stockId(stockId)
                    .name(name)
                    .oldName(name) // 首次入库 old_name 与 name 相同
                    .isStib(ClsValueUtil.toBool(map.get("is_stib"), false))
                    .build());
        }
        return result;
    }

    private static List<ClsStockLink> parseStockLinks(Object raw, long articleId) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<ClsStockLink> result = new ArrayList<>();
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m)) continue;
            Map<String, Object> map = ClsValueUtil.coerceMap(m);
            result.add(ClsStockLink.builder()
                    .articleId(articleId)
                    .stockId(ClsValueUtil.asStr(map.get("StockID")))
                    .lastPrice(ClsValueUtil.toBigDecimal(map.get("last")))
                    .riseRange(ClsValueUtil.toBigDecimal(map.get("RiseRange")))
                    .build());
        }
        return result;
    }
}
