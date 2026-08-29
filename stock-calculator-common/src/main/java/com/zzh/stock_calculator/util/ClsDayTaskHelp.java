package com.zzh.stock_calculator.util;

import com.zzh.stock_calculator.dto.*;
import lombok.extern.slf4j.Slf4j;

import java.util.*;

@Slf4j
public class ClsDayTaskHelp {

    public static Map<String,String> getHeader() {
        Map<String,String> headMaps= new HashMap<>();
        headMaps.put("User-Agent","Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
        headMaps.put("Referer","https://www.cls.cn/telegraph");
        headMaps.put("Pragma","no-cache");
        return headMaps;
    }

    public static Map<String, Object> getClsCachePraml() {

        Map<String, Object> params = new TreeMap<>();

        params.put("app", "CailianpressWeb");
        params.put("name", "telegraph");
        params.put("os", "web");
        params.put("sv", "8.7.9");

        String sign = ClsSignUtil.getSign(params);
        params.put("sign", sign);
        HttpUtil.getUrlInfo(params);
        return params;
    }

    public static Map<String,Object> getClsRollheParm(long time,int refreshType,int num) {

        if(refreshType != 1 && refreshType != 2) {
            refreshType = 1;
        }

        Map<String, Object> params = new TreeMap<>();
        params.put("app", "CailianpressWeb");
        params.put("last_time",time); // 当前时间戳（秒）
        params.put("os", "web");
        params.put("refresh_type", refreshType);
        params.put("rn", num);
        params.put("sv", "8.7.9");
        return params;

    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> coerceMap(Map<?, ?> raw) {
        Map<String, Object> map = new HashMap<>();
        raw.forEach((k, v) -> map.put(String.valueOf(k), v));
        return map;
    }

    // ========== 题材解析 ==========

    @SuppressWarnings("unchecked")
    public static List<ClsSubject> parseSubjectDicts(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<ClsSubject> result = new ArrayList<>();
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m)) continue;
            Map<String, Object> map = coerceMap(m);
            result.add(ClsSubject.builder()
                    .subjectId(ParseDataUtil.toLong(map.get("subject_id"), 0L))
                    .subjectName(ParseDataUtil.asStr(map.get("subject_name")))
                    .plateId(ParseDataUtil.toLong(map.get("plate_id"), null))
                    .channel(ParseDataUtil.asStr(map.get("channel")))
                    .build());
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    public static List<ClsArticleSubject> parseSubjectLinks(Object raw, long articleId) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<ClsArticleSubject> result = new ArrayList<>();
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m)) continue;
            Map<String, Object> map = coerceMap(m);
            result.add(ClsArticleSubject.builder()
                    .articleId(articleId)
                    .subjectId(ParseDataUtil.toLong(map.get("subject_id"), 0L))
                    .build());
        }
        return result;
    }


    // ========== 股票解析 ==========
    @SuppressWarnings("unchecked")
    public static List<Stock> parseStockDicts(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<Stock> result = new ArrayList<>();
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m)) continue;
            Map<String, Object> map = coerceMap(m);
            String stockId = ParseDataUtil.asStr(map.get("StockID"));
            if (stockId == null || stockId.isBlank()) continue;
            String name = ParseDataUtil.asStr(map.get("name"));
            result.add(Stock.builder()
                    .stockId(stockId)
                    .name(name)
                    .oldName(name)  // 首次入库 old_name 与 name 相同
                    .isStib(ParseDataUtil.toBool(map.get("is_stib"), false))
                    .build());
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    public static List<ClsArticleStock> parseStockLinks(Object raw, long articleId) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<ClsArticleStock> result = new ArrayList<>();
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m)) continue;
            Map<String, Object> map = coerceMap(m);
            result.add(ClsArticleStock.builder()
                    .articleId(articleId)
                    .stockId(ParseDataUtil.asStr(map.get("StockID")))
                    .lastPrice(ParseDataUtil.toBigDecimal(map.get("last")))
                    .riseRange(ParseDataUtil.toBigDecimal(map.get("RiseRange")))
                    .build());
        }
        return result;
    }



}
