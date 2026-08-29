package com.zzh.stock_calculator.task;

import com.zzh.stock_calculator.util.HttpUtil;
import com.zzh.stock_calculator.util.ParseDataUtil;
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

import com.zzh.stock_calculator.service.ClsArticleService;
import com.zzh.stock_calculator.util.ClsSignUtil;
import com.zzh.stock_calculator.service.CommonHttpService;


@Slf4j
@Component
@RequiredArgsConstructor
public class TaskService {

    private final CommonHttpService commonHttpService;
    private final TaskService clsArticleService;

    @Scheduled(fixedDelay = 8*60*1000)
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

        // 当前时间的数字 从当前时间滚动到截至时间
        long latestLocalTimestamp = Instant.now().getEpochSecond();

        // 1. 查询本地数据库中最新的一条文章的时间戳 截至时间
        ClsArticle maxCtimeByClsArticle = clsArticleService.getMaxCtimeByClsArticle();
        Long tableTime = (Long) maxCtimeByClsArticle.getCtime();

        if (tableTime == null) {
            // 如果本地是空库，说明是首次冷启动，按初始策略拉取（如最近 3 天）
            //latestLocalTimestamp = System.currentTimeMillis() / 1000 - (3 * 24 * 3600);
            needContinue = false;
        }

        long tempTime = latestLocalTimestamp;
        int count = 0;//插入条数
        int forCount = 0;

        while (needContinue) {

            forCount++;

            List<?> rollData = getRollData(tempTime,1);

            for (Object item : rollData) {

                if (!(item instanceof Map<?, ?> raw)) {
                    continue;
                }

                Map<String, Object> stringObjectMap = TaskServiceHelp.coerceMap(raw);
                long time = ParseDataUtil.toLong(stringObjectMap.get("ctime"),0);

                // 获取下一次查询的时间起点
                if(time <= tempTime) {
                    tempTime = time;
                }

            }

            if (tempTime <= tableTime) {
                // 只要当前列表里的某条数据时间已经 <= 本地最大时间，说明数据链条已经接上
                needContinue = false;
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
                long sleepTime = ThreadLocalRandom.current().nextLong(5000, 7000);
                TimeUnit.MILLISECONDS.sleep(sleepTime);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); // 保持中断标志位
                log.warn("同步任务休眠被中断", e);
                break; // 如果外部终止应用，安全退出循环
            }
        }

        log.info(">>> 停机期间数据补偿完成！进入日常定时增量抓取模式。");
    }

    @Scheduled(fixedDelay = 60*1000)
    public void fixedHIstoryDataTask() {

        log.info("历史补库任务开始");
        // 现在时间
        long maxTime = 1787903769;
        // 三年前的数据
        long compareTime = 1693320189;
        // 截至时间
        long endTime = compareTime-10000;

        // 向下滚动
        Long firstByOrderByCtimeDescwhereCtime = clsArticleService.findHistoryMinCtime(endTime,maxTime);
        if(firstByOrderByCtimeDescwhereCtime != null) {
            maxTime = firstByOrderByCtimeDescwhereCtime;
        }

        log.info("历史补库任务开始时间：{}",maxTime);

        if(maxTime >= compareTime) {

            List<?> cacheData = getRollData(maxTime,1);
            if(cacheData != null) {
                int count = processData(cacheData);
            }
            log.warn("运行中的定时任务结束");
        } else {
            log.info("历史补库任务完全结束，请停止这个定时任务！！！");
        }

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
                if (processRollItem(TaskServiceHelp.coerceMap(raw))) {
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





    private Map<String,String> getHeader() {
        Map<String,String> headMaps= new HashMap<>();
        headMaps.put("User-Agent","Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
        headMaps.put("Referer","https://www.cls.cn/telegraph");
        headMaps.put("Pragma","no-cache");
        return headMaps;
    }


    private Map<String, Object> getClsCachePraml() {

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

    private Map<String,Object> getClsRollheParm(long time,int refreshType) {

        if(refreshType != 1 && refreshType != 2) {
            refreshType = 1;
        }

        Map<String, Object> params = new TreeMap<>();
        params.put("app", "CailianpressWeb");
        params.put("last_time",time); // 当前时间戳（秒）
        params.put("os", "web");
        params.put("refresh_type", refreshType);
        params.put("rn", 50);
        params.put("sv", "8.7.9");
        return params;

    }
}
