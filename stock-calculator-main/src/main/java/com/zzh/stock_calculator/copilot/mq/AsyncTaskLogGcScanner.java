package com.zzh.stock_calculator.copilot.mq;

import com.zzh.stock_calculator.copilot.config.AsyncTaskProperties;
import com.zzh.stock_calculator.copilot.entity.UserAsyncTaskLog;
import com.zzh.stock_calculator.copilot.repository.UserAsyncTaskLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

/**
 * 异步任务映射 GC 兜底扫描（步 6-4b，memory 定案②双保险的第二道）：
 * 终态事件驱动清映射是第一道（AsyncTaskResultConsumer）；本扫描兜住「事件丢失」
 * 场景（orchestration 重启丢消息 / MQ 队列过期）——超期仍 RUNNING 的映射置 TIMEOUT，
 * 释放限流并发额度（countByUserIdAndStatus 不再计入）。
 * <p>阈值取限流频控窗口的 20 倍（默认 20 分钟）：远大于正常长任务耗时，
 * 又不至于让僵尸映射占并发额度过夜。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AsyncTaskLogGcScanner {

    private final UserAsyncTaskLogRepository logRepository;
    private final AsyncTaskProperties properties;

    @Scheduled(fixedDelayString = "PT5M")
    @Transactional
    public void scan() {
        OffsetDateTime deadline = OffsetDateTime.now()
                .minusSeconds(properties.getRateLimit().getFrequencyWindowSeconds() * 20L);
        logRepository.findByStatus(UserAsyncTaskLog.STATUS_RUNNING).stream()
                .filter(row -> row.getCreatedAt().isBefore(deadline))
                .forEach(row -> {
                    int updated = logRepository.finalizeIfRunning(row.getTaskId(), UserAsyncTaskLog.STATUS_TIMEOUT);
                    if (updated > 0) {
                        log.warn("[async-channel] GC 超期 RUNNING 映射置 TIMEOUT correlationId={} taskId={} createdAt={}",
                                row.getCorrelationId(), row.getTaskId(), row.getCreatedAt());
                    }
                });
    }
}
