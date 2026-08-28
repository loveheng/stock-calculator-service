package com.zzh.stock_calculator.task;

import lombok.RequiredArgsConstructor;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

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

        List<?> cacheData = getCacheData();
        if(cacheData != null) {
            processData(cacheData);
        }

    }

    /**
     * 系统启动时自动执行补录
     */
    @EventListener(ApplicationReadyEvent.class)
    public void syncOnStartup() {

        try {
            // 延迟 5 秒（可根据需要调整为 3~10 秒）
            log.info(">>> 应用已就绪，补录任务将在 10 秒后开始执行...");
            TimeUnit.SECONDS.sleep(10);
            log.info(">>> 开始执行停机历史数据对齐...");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("补录任务延迟等待被中断", e);
        }

        boolean needContinue = true;

        // 获取当下时间
        // 获取库中最大的时间
        // 通过接口向上滚动
        log.info(">>> 开始检查并补偿停机期间的财联社数据...");

        // 当前时间的数字
        long latestLocalTimestamp = Instant.now().getEpochSecond();

        // 1. 查询本地数据库中最新的一条文章的时间戳
        ClsArticle maxCtimeByClsArticle = clsArticleService.getMaxCtimeByClsArticle();
        Long tableTime = (Long) maxCtimeByClsArticle.getCtime();

        if (tableTime == null) {
            // 如果本地是空库，说明是首次冷启动，按初始策略拉取（如最近 3 天）
            //latestLocalTimestamp = System.currentTimeMillis() / 1000 - (3 * 24 * 3600);
            needContinue = false;
        }

        long tempTime = tableTime;
        int count = 0;//插入条数
        int forCount = 0;
        while (needContinue) {

            forCount++;

            List<?> rollData = getRollData(tempTime,2);

            for (Object item : rollData) {

                if (!(item instanceof Map<?, ?> raw)) {
                    continue;
                }

                Map<String, Object> stringObjectMap = coerceMap(raw);
                long time = toLong(stringObjectMap.get("ctime"),0);

                // 获取下一次查询的时间起点
                if(time >= tempTime) {
                    tempTime = time;
                }

                if (time > latestLocalTimestamp) {
                    // 只要当前列表里的某条数据时间已经 <= 本地最大时间，说明数据链条已经接上
                    needContinue = false;
                }

            }

            if(rollData !=null) {
                count = processData(rollData);
            }

            // 如果时间大于其他其中基准时间 或者 插入条数为0 或者循环次数大于5
            if(!needContinue || count ==0 || forCount >= 10) {
                break;
            }

            // 避免高频请求，微休眠
            try {
                long sleepTime = ThreadLocalRandom.current().nextLong(2000, 3000);
                TimeUnit.MILLISECONDS.sleep(sleepTime);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); // 保持中断标志位
                log.warn("同步任务休眠被中断", e);
                break; // 如果外部终止应用，安全退出循环
            }
        }

        log.info(">>> 停机期间数据补偿完成！进入日常定时增量抓取模式。");
    }



    /**
     * 获取当下最新的数据和处理
     */
    private List<?> getCacheData() {

        Map<String, Object> result = getClsCache();
        Object dataObj = result.get("data");
        if (!(dataObj instanceof Map<?, ?> dataMap)) {
            return null;
        }
        Object rollObj = dataMap.get("roll_data");
        if (!(rollObj instanceof List<?> rollList)) {
            return null;
        }

        return rollList;
    }

    /**
     * 获取当下滚动的数据和处理
     */
    private List<?> getRollData(long time,int refreshType) {

        Map<String, Object> result = getRollerList(time,refreshType);

        Object dataObj = result.get("data");

        if (!(dataObj instanceof Map<?, ?> dataMap)) {
            return null;
        }

        Object rollObj = dataMap.get("roll_data");
        if (!(rollObj instanceof List<?> rollList)) {
            return null;
        }

        return rollList;
    }

    /**
     * 数据处理完成之后的入库
     * @param data
     */
    private int processData (List<?> data) {

        int newCount = 0;
        for (Object item : data) {
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
        return newCount;
    }

    /**
     * 获取模拟最新的数据
     */
    private Map<String, Object> getClsCache() {
        Map<String, Object> params = getClsCachePraml();
        Map<String, Object> result = commonHttpService.get("https://www.cls.cn/api/cache", Map.class, params, getHeader());
        return result;
    }


    /**
     * 获取模拟滚动的数据
     */
    private Map<String,Object> getRollerList(long time,int refreshType) {

        Map<String, Object> rollheParam = getClsRollheParm(time,refreshType);
        String sign = ClsSignUtil.getSign(rollheParam);
        rollheParam.put("sign", sign);
        Map<String, Object> result = commonHttpService.get("https://www.cls.cn/v1/roll/get_roll_list", Map.class, rollheParam, getHeader());
        return result;

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

    private Map<String, Object> getClsCachePraml() {

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

    private Map<String,Object> getClsRollheParm(long time,int refreshType) {

        if(refreshType != 1 || refreshType!=2) {
            refreshType = 1;
        }

        Map<String, Object> params = new TreeMap<>();
        params.put("app", "CailianpressWeb");
        params.put("last_time",time); // 当前时间戳（秒）
        params.put("os", "web");
        params.put("refresh_type", refreshType);
        params.put("rn", 5);
        params.put("sv", "8.7.9");
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