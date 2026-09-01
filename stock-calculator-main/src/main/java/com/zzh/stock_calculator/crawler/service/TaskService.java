package com.zzh.stock_calculator.crawler.service;
import com.zzh.stock_calculator.crawler.entity.*;
import com.zzh.stock_calculator.crawler.util.ClsDayTaskHelp;
import com.zzh.stock_calculator.crawler.util.ClsSignUtil;
import com.zzh.stock_calculator.crawler.util.ParseDataUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class TaskService {

    private final ClsArticleService clsArticleService;
    private final CommonHttpService commonHttpService;

    public int processData (List<?> data) {

        int newCount = 0;
        for (Object item : data) {
            if (!(item instanceof Map<?, ?> raw)) {
                continue;
            }
            try {
                if (processRollItem(ClsDayTaskHelp.coerceMap(raw))) {
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
     * 处理单条拍客数据，解析并保存
     */
    public boolean processRollItem(Map<String, Object> item) {
        Object idRaw = item.get("id");
        if (idRaw == null) {
            return false;
        }
        long articleId = ((Number) idRaw).longValue();

        ClsArticle article = ClsArticle.builder()
                .id(articleId)
                .type(ParseDataUtil.toInt(item.get("type"), -1))
                .title(ParseDataUtil.asStr(item.get("title")))
                .brief(ParseDataUtil.asStr(item.get("brief")))
                .content(ParseDataUtil.asStr(item.get("content")))
                .ctime(ParseDataUtil.toLong(item.get("ctime"), 0L))
                .author(ParseDataUtil.asStr(item.getOrDefault("author", "")))
                .level(ParseDataUtil.asStr(item.getOrDefault("level", "C")))
                .images(ParseDataUtil.parseJsonStrList(item.get("images")))
                .audioUrl(ParseDataUtil.parseJsonStrList(item.get("audio_url")))
                .build();

        // 解析题材：字典 + 关联
        Object subjectRaw = item.get("subjects");
        List<ClsSubject> subjectDicts = ClsDayTaskHelp.parseSubjectDicts(subjectRaw);
        List<ClsArticleSubject> subjectLinks = ClsDayTaskHelp.parseSubjectLinks(subjectRaw, articleId);

        // 解析股票：字典 + 关联
        Object stockRaw = item.get("stock_list");
        List<Stock> stockDicts = ClsDayTaskHelp.parseStockDicts(stockRaw);
        List<ClsArticleStock> stockLinks = ClsDayTaskHelp.parseStockLinks(stockRaw, articleId);

        // 一次性事务写入
        return clsArticleService.saveArticleWithRelations(
                article, subjectLinks, stockLinks, stockDicts, subjectDicts);
    }

    public List<?> getRollData(long time, int refreshType) {
        Map<String, Object> rollheParam = ClsDayTaskHelp.getClsRollheParm(time, refreshType,50);
        String sign = ClsSignUtil.getSign(rollheParam);
        rollheParam.put("sign", sign);
        Map<String, Object> result = commonHttpService.get("https://www.cls.cn/v1/roll/get_roll_list", Map.class, rollheParam, ClsDayTaskHelp.getHeader());

        if (result != null && result.get("data") instanceof Map<?, ?> dataMap) {
            if (dataMap.get("roll_data") instanceof List<?> rollList) {
                return rollList;
            }
        }
        return Collections.emptyList();
    }

    public Map<String, Object> getClsCache() {
        Map<String, Object> params = ClsDayTaskHelp.getClsCachePraml();
        Map<String, Object> result = commonHttpService.get("https://www.cls.cn/api/cache", Map.class, params, ClsDayTaskHelp.getHeader());
        return result;
    }


    public ClsArticle getMaxCtimeByClsArticle() {
        return clsArticleService.getMaxCtimeByClsArticle();
    }


    public Long findHistoryMinCtime(Long sartTime,Long endTime) {
        return clsArticleService.findHistoryMinCtime(sartTime, endTime);
    }

}
