package com.zzh.stock_calculator.task;

import lombok.RequiredArgsConstructor;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

import org.springframework.scheduling.annotation.Scheduled;

import com.zzh.stock_calculator.dto.ClsArticle;
import com.zzh.stock_calculator.dto.ClsArticleStock;
import com.zzh.stock_calculator.dto.ClsArticleSubject;
import com.zzh.stock_calculator.dto.ClsSubject;
import com.zzh.stock_calculator.dto.Stock;
import com.zzh.stock_calculator.service.ClsArticleService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzh.stock_calculator.util.ClsSignUtil;
import com.zzh.stock_calculator.util.CommonHttpService;


@Slf4j
@Component
@RequiredArgsConstructor
public class TaskService {

    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();
    private static final TypeReference<List<String>> JSON_STRING_LIST_TYPE =
            new TypeReference<List<String>>() {};

    private final CommonHttpService commonHttpService;
    private final ClsArticleService clsArticleService;

    @Scheduled(fixedDelay = 5000)
    public void fixedDelayTask() {
        Map<String, Object> params = getClsUrl();
        Map<String, Object> result = commonHttpService.get("https://www.cls.cn/api/cache", Map.class, params, getHeader());

        Object dataObj = result.get("data");
        if (!(dataObj instanceof Map<?, ?> dataMap)) {
            return;
        }
        Object rollObj = dataMap.get("roll_data");
        if (!(rollObj instanceof List<?> rollList)) {
            return;
        }

        int newCount = 0;
        for (Object item : rollList) {
            if (!(item instanceof Map<?, ?> raw)) {
                continue;
            }
            try {
                if (processRollItem(coerceMap(raw))) {
                    newCount++;
                }
            } catch (Exception e) {
                log.warn("failed to process roll item, id={}", raw.get("id"), e);
            }
        }
        if (newCount > 0) {
            log.info("saved {} new articles from this fetch", newCount);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> coerceMap(Map<?, ?> raw) {
        Map<String, Object> map = new HashMap<>();
        raw.forEach((k, v) -> map.put(String.valueOf(k), v));
        return map;
    }

    /**
     * 处理单条拍客数据，解析并保存
     */
    private boolean processRollItem(Map<String, Object> item) {
        Object idRaw = item.get("id");
        if (idRaw == null) {
            return false;
        }
        long articleId = ((Number) idRaw).longValue();

        ClsArticle article = ClsArticle.builder()
                .id(articleId)
                .type(toInt(item.get("type"), -1))
                .title(asStr(item.get("title")))
                .brief(asStr(item.get("brief")))
                .content(asStr(item.get("content")))
                .ctime(toLong(item.get("ctime"), 0L))
                .author(asStr(item.getOrDefault("author", "")))
                .level(asStr(item.getOrDefault("level", "C")))
                .images(parseJsonStrList(item.get("images")))
                .audioUrl(parseJsonStrList(item.get("audio_url")))
                .build();

        // 解析题材：字典 + 关联
        Object subjectRaw = item.get("subjects");
        List<ClsSubject> subjectDicts = parseSubjectDicts(subjectRaw);
        List<ClsArticleSubject> subjectLinks = parseSubjectLinks(subjectRaw, articleId);

        // 解析股票：字典 + 关联
        Object stockRaw = item.get("stock_list");
        List<Stock> stockDicts = parseStockDicts(stockRaw);
        List<ClsArticleStock> stockLinks = parseStockLinks(stockRaw, articleId);

        // 一次性事务写入
        return clsArticleService.saveArticleWithRelations(
                article, subjectLinks, stockLinks, stockDicts, subjectDicts);
    }

    // ========== 题材解析 ==========

    @SuppressWarnings("unchecked")
    private List<ClsSubject> parseSubjectDicts(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<ClsSubject> result = new ArrayList<>();
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m)) continue;
            Map<String, Object> map = coerceMap(m);
            result.add(ClsSubject.builder()
                    .subjectId(toLong(map.get("subject_id"), 0L))
                    .subjectName(asStr(map.get("subject_name")))
                    .plateId(toLong(map.get("plate_id"), null))
                    .channel(asStr(map.get("channel")))
                    .build());
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private List<ClsArticleSubject> parseSubjectLinks(Object raw, long articleId) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<ClsArticleSubject> result = new ArrayList<>();
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m)) continue;
            Map<String, Object> map = coerceMap(m);
            result.add(ClsArticleSubject.builder()
                    .articleId(articleId)
                    .subjectId(toLong(map.get("subject_id"), 0L))
                    .build());
        }
        return result;
    }

    // ========== 股票解析 ==========

    @SuppressWarnings("unchecked")
    private List<Stock> parseStockDicts(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<Stock> result = new ArrayList<>();
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m)) continue;
            Map<String, Object> map = coerceMap(m);
            String stockId = asStr(map.get("StockID"));
            if (stockId == null || stockId.isBlank()) continue;
            String name = asStr(map.get("name"));
            result.add(Stock.builder()
                    .stockId(stockId)
                    .name(name)
                    .oldName(name)  // 首次入库 old_name 与 name 相同
                    .isStib(toBool(map.get("is_stib"), false))
                    .build());
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private List<ClsArticleStock> parseStockLinks(Object raw, long articleId) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<ClsArticleStock> result = new ArrayList<>();
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m)) continue;
            Map<String, Object> map = coerceMap(m);
            result.add(ClsArticleStock.builder()
                    .articleId(articleId)
                    .stockId(asStr(map.get("StockID")))
                    .lastPrice(toBigDecimal(map.get("last")))
                    .riseRange(toBigDecimal(map.get("RiseRange")))
                    .build());
        }
        return result;
    }

    // ========== JSON 数组解析 ==========

    @SuppressWarnings("unchecked")
    private List<String> parseJsonStrList(Object raw) {
        if (raw instanceof List<?> list) {
            List<String> result = new ArrayList<>();
            for (Object o : list) {
                if (o != null) result.add(String.valueOf(o));
            }
            return result.isEmpty() ? null : result;
        }
        if (raw instanceof String str && !str.isBlank() && str.startsWith("[")) {
            try {
                return JSON_MAPPER.readValue(str, JSON_STRING_LIST_TYPE);
            } catch (Exception e) {
                log.warn("failed to parse JSON array: {}", str);
            }
        }
        return null;
    }

    // ========== 类型转换工具 ==========

    private static String asStr(Object val) {
        return val == null ? null : String.valueOf(val);
    }

    private static int toInt(Object val, int defaultVal) {
        if (val instanceof Number n) return n.intValue();
        if (val instanceof String s) {
            try { return Integer.parseInt(s); } catch (NumberFormatException ignored) {}
        }
        return defaultVal;
    }

    private static Long toLong(Object val, Long defaultVal) {
        if (val instanceof Number n) return n.longValue();
        if (val instanceof String s) {
            try { return Long.parseLong(s); } catch (NumberFormatException ignored) {}
        }
        return defaultVal;
    }

    private static long toLong(Object val, long defaultVal) {
        if (val instanceof Number n) return n.longValue();
        if (val instanceof String s) {
            try { return Long.parseLong(s); } catch (NumberFormatException ignored) {}
        }
        return defaultVal;
    }

    private static java.math.BigDecimal toBigDecimal(Object val) {
        if (val instanceof Number n) return java.math.BigDecimal.valueOf(n.doubleValue());
        if (val instanceof String s) {
            try { return new java.math.BigDecimal(s); } catch (NumberFormatException ignored) {}
        }
        return null;
    }

    private static boolean toBool(Object val, boolean defaultVal) {
        if (val instanceof Boolean b) return b;
        if (val instanceof String s) return "true".equalsIgnoreCase(s) || "1".equals(s);
        if (val instanceof Number n) return n.intValue() == 1;
        return defaultVal;
    }

    private Map<String, Object> getClsUrl() {

        Map<String, Object> params = new TreeMap<>();

        params.put("app", "CailianpressWeb");
        params.put("name", "telegraph");
        params.put("os", "web");
        params.put("sv", "8.7.9");

        String sign = ClsSignUtil.getSign(params);
        params.put("sign", sign);
        getUrlInfo(params);
        return params;

    }

    private Map<String,String> getHeader() {
        Map<String,String> headMaps= new HashMap<>();
        headMaps.put("User-Agent","Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
        headMaps.put("Referer","https://www.cls.cn/telegraph");
        headMaps.put("Pragma","no-cache");

        return headMaps;
    }


    private void getUrlInfo(Map<String, Object> params) {

        StringBuilder queryString = new StringBuilder();
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            if (queryString.length() > 0) queryString.append("&");
            queryString.append(entry.getKey()).append("=").append(entry.getValue());
        }
        log.info(queryString.toString());
    }

}