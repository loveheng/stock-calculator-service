package com.zzh.stock_calculator.crawler.task;
import com.zzh.stock_calculator.crawler.service.TaskService;
import lombok.RequiredArgsConstructor;

import lombok.extern.slf4j.Slf4j;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.*;

import org.springframework.scheduling.annotation.Scheduled;


@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "crawler", name = "enabled", havingValue = "true", matchIfMissing = false)
public class ClsDayTask {

    private final TaskService taskService;

    @Scheduled(fixedDelay = 8*60*1000)
    public void fixedDelayTask() {

        List<?> cacheData = getCacheData();
        if(cacheData != null) {
            taskService.processData(cacheData);
        }
    }

    /**
     * 获取当下最新的数据和处理
     */
    private List<?> getCacheData() {

        Map<String, Object> result = taskService.getClsCache();
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


}
