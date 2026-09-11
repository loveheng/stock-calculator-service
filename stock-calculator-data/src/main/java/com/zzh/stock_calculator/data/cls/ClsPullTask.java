package com.zzh.stock_calculator.data.cls;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * CLS 电报定时拉取（对齐原 main 模块 ClsDayTask 的 8 分钟节奏）。
 * 仅 collector 角色启用（datasvc.collector.enabled=true）；worker 部署不装配。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "datasvc.collector", name = "enabled", havingValue = "true")
public class ClsPullTask {

    private final ClsCollectorService collectorService;

    @Scheduled(fixedDelay = 8 * 60 * 1000)
    public void pull() {
        collectorService.pullAndPublish();
    }
}
