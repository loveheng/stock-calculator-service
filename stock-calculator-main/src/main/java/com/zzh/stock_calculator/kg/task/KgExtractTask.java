package com.zzh.stock_calculator.kg.task;

import com.zzh.stock_calculator.kg.mq.KgExtractPublisher;
import com.zzh.stock_calculator.monitor.AppTaskHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * KG 抽取定时任务（MQ 单路径终态）：PENDING 扫描发布 task.kg.extract，抽取由数据服务
 * worker 承担（对账语义：未终态每日重发）。调度由 pull_task_config CALENDAR 行表驱动
 * （CalendarTaskClaimScheduler 认领 job.kg.extract）。
 * <p>注：与契约消息 DTO com.zzh.stockcalc.contract.message.KgExtractTask 同名不同包——
 * 契约侧是消息 DTO，本类是 PENDING 扫描发布器入口（AnnouncementProcessTask 先例）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KgExtractTask implements AppTaskHandler {

    private final KgExtractPublisher publisher;

    @Override
    public String taskCode() {
        return AppTaskHandler.TASK_KG_EXTRACT;
    }

    @Override
    public void run() {
        publisher.publishPendingBatch();
    }
}
