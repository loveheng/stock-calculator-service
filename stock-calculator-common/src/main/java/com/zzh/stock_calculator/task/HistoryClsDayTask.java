package com.zzh.stock_calculator.task;


import com.zzh.stock_calculator.service.TaskService;
import com.zzh.stock_calculator.util.ClsDayTaskHelp;
import com.zzh.stock_calculator.util.ParseDataUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "crawler", name = "enabled", havingValue = "true", matchIfMissing = false)
public class HistoryClsDayTask {

    // 任务运行状态控制标记（默认为 false）
    private final AtomicBoolean isRunning = new AtomicBoolean(false);

    private final TaskService taskService;


    /**
     * 异步启动历史回溯补库
     */
    @Async
    public void startHistorySync(long maxTime, long compareTime) {
        if (!isRunning.compareAndSet(false, true)) {
            log.warn("历史补库任务已在运行中，请勿重复触发！");
            return;
        }

        long endTime = compareTime - 10000;

        // 1. 获取区间内已有数据的断点最小时间
        Long dbMinCtime = taskService.findHistoryMinCtime(endTime, maxTime);
        long currentCursor;

        if (dbMinCtime != null && dbMinCtime > 0) {
            currentCursor = dbMinCtime;
            log.info(">>> 识别到历史区间断点，继续向下滚动，起点时间戳: {}", currentCursor);
        } else {
            currentCursor = maxTime;
            log.info(">>> 历史区间无数据，从最大边界开始冷启动，起点时间戳: {}", currentCursor);
        }

        // 2. 检查是否已达到目标终点
        if (currentCursor <= compareTime) {
            log.info(">>> 当前游标 ({}) 已触达或早于目标时间 ({})，无需补录。", currentCursor, compareTime);
            isRunning.set(false);
            return;
        }

        log.info(">>> 历史补库异步线程启动，目标截止时间戳: {}", compareTime);

        // 3. 执行核心循环
        try {
            int emptyOrErrorCount = 0;

            while (isRunning.get() && currentCursor > compareTime) {
                List<?> rollData = null;
                try {
                    rollData = taskService.getRollData(currentCursor, 1);
                } catch (Exception e) {
                    log.error("抓取财联社历史数据请求异常，游标: {}", currentCursor, e);
                    emptyOrErrorCount++;
                    handleBackoff(emptyOrErrorCount);
                    continue;
                }

                if (rollData == null || rollData.isEmpty()) {
                    emptyOrErrorCount++;
                    log.warn("未获取到历史数据，游标: {}, 连续空数据次数: {}", currentCursor, emptyOrErrorCount);
                    if (emptyOrErrorCount >= 5) {
                        log.error("连续 5 次未能获取有效数据，任务自动中断防护。");
                        break;
                    }
                    currentCursor -= 1; // 强制下移步进打破真空期
                    handleBackoff(emptyOrErrorCount);
                    continue;
                }

                // 提取批次内最小时间
                long minTimeInBatch = currentCursor;
                for (Object item : rollData) {
                    if (item instanceof Map<?, ?> raw) {
                        Map<String, Object> map = ClsDayTaskHelp.coerceMap(raw);
                        long ctime = ParseDataUtil.toLong(map.get("ctime"), 0L);
                        if (ctime > 0 && ctime < minTimeInBatch) {
                            minTimeInBatch = ctime;
                        }
                    }
                }

                // 数据落库
                int savedCount = taskService.processData(rollData);
                log.info("成功处理 {} 条数据，游标自 {} 推移至 {}", savedCount, currentCursor, minTimeInBatch);

                // 推进游标并防止死锁
                if (minTimeInBatch >= currentCursor) {
                    currentCursor -= 1;
                } else {
                    currentCursor = minTimeInBatch;
                }

                // 重置异常计数
                emptyOrErrorCount = 0;

                // 动态根据交易日/闲时进行自适应休眠
                long sleepMillis = getDynamicSleepMillis();
                TimeUnit.MILLISECONDS.sleep(sleepMillis);
            }

            log.info(">>> 历史补库任务执行完毕或已平稳退出！");

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("历史补库任务被外部中断");
        } finally {
            isRunning.set(false);
        }
    }

    /**
     * 手动停止任务
     */
    public void stopSync() {
        isRunning.set(false);
        log.info("已发送停止指令，任务将在当前批次结束后平稳退出。");
    }

    public boolean isTaskRunning() {
        return isRunning.get();
    }

    // ========== 动态频控策略 ==========
    private long getDynamicSleepMillis() {
        ZonedDateTime now = ZonedDateTime.now(ZoneId.of("Asia/Shanghai"));
        DayOfWeek dayOfWeek = now.getDayOfWeek();
        LocalTime time = now.toLocalTime();

        boolean isWeekend = (dayOfWeek == DayOfWeek.SATURDAY || dayOfWeek == DayOfWeek.SUNDAY);
        boolean isTradingHours = !isWeekend
                && time.isAfter(LocalTime.of(9, 15))
                && time.isBefore(LocalTime.of(15, 30));
        boolean isLunchBreak = !isWeekend
                && time.isAfter(LocalTime.of(11, 35))
                && time.isBefore(LocalTime.of(12, 55));

        if (isTradingHours && !isLunchBreak) {
            // 交易时段：慢速防风控 (8s ~ 15s)
            return ThreadLocalRandom.current().nextLong(8000, 15000);
        } else if (isLunchBreak) {
            // 午休缓冲期：中速 (4s ~ 7s)
            return ThreadLocalRandom.current().nextLong(4000, 7000);
        } else {
            // 闲时 (晚间/周末)：快速拉取 (1.5s ~ 3s)
            return ThreadLocalRandom.current().nextLong(1500, 3000);
        }
    }

    private void handleBackoff(int failureCount) throws InterruptedException {
        long backoff = Math.min(10000L * failureCount, 60000L);
        log.warn("接口暂无数据或异常，避让休眠 {} ms...", backoff);
        TimeUnit.MILLISECONDS.sleep(backoff);
    }

    /*@Scheduled(fixedDelay = 60*1000)
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
                int count = taskService.processData(cacheData);
            }
            log.warn("运行中的定时任务结束");
        } else {
            log.info("历史补库任务完全结束，请停止这个定时任务！！！");
        }

    }*/




}
