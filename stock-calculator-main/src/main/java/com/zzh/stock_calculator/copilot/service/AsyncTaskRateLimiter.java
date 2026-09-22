package com.zzh.stock_calculator.copilot.service;

import com.zzh.stock_calculator.copilot.config.AsyncTaskProperties;
import com.zzh.stock_calculator.copilot.entity.UserAsyncTaskLog;
import com.zzh.stock_calculator.copilot.repository.UserAsyncTaskLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;

/**
 * 用户维度异步任务限流器（步 6-4a，memory 定案③）：限流全卡 main——
 * 并发上限（在途 RUNNING 任务数）+ 频控（时间窗创建次数），双闸门都在
 * createAsyncTask 入口前置拦截；orchestration 只做系统级全局并发上限（v2）。
 * <p>计数数据源 = user_async_task_log（步 6-1 已建）：并发查 status=RUNNING，
 * 频控查 created_at 时间窗；数据随任务天然过期，无额外清理成本。
 */
@Component
@RequiredArgsConstructor
public class AsyncTaskRateLimiter {

    private final UserAsyncTaskLogRepository logRepository;
    private final AsyncTaskProperties properties;

    /** 并发闸门：在途 RUNNING 数达上限即拒（429 语义） */
    public void checkConcurrency(String userId) {
        int running = (int) logRepository.countByUserIdAndStatus(
                userId, UserAsyncTaskLog.STATUS_RUNNING);
        if (running >= properties.getRateLimit().getMaxConcurrent()) {
            throw new com.zzh.stock_calculator.common.BusinessException(429,
                    "异步任务并发已达上限（" + running + "/" + properties.getRateLimit().getMaxConcurrent()
                            + "），请等待在途任务完成");
        }
    }

    /** 频控闸门：时间窗内创建次数达上限即拒 */
    public void checkFrequency(String userId) {
        OffsetDateTime windowStart = OffsetDateTime.now()
                .minusSeconds(properties.getRateLimit().getFrequencyWindowSeconds());
        long count = logRepository.countByUserIdAndCreatedAtAfter(userId, windowStart);
        if (count >= properties.getRateLimit().getMaxPerWindow()) {
            throw new com.zzh.stock_calculator.common.BusinessException(429,
                    "创建过于频繁（" + count + " 次/" + properties.getRateLimit().getFrequencyWindowSeconds()
                            + "s 窗口上限 " + properties.getRateLimit().getMaxPerWindow() + "），请稍后再试");
        }
    }
}
