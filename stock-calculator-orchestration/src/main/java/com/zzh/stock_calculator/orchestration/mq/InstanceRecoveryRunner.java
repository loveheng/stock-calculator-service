package com.zzh.stock_calculator.orchestration.mq;

import com.zzh.stock_calculator.orchestration.entity.TaskInstanceEntity;
import com.zzh.stock_calculator.orchestration.executor.Executor;
import com.zzh.stock_calculator.orchestration.repository.TaskInstanceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 进程重启恢复器（§八 可靠性收口，查漏二批②）：TaskInstanceRepository.findByStatus
 * (ST_RUNNING) 的「重启恢复」注释此前无任何调用方——重启后 running 实例成孤儿
 * （advisory lock 事务级已随崩溃释放），重启窗口内完成的冒烟实例致 plan 停 draft、
 * use_count/终态事件全丢。应用就绪后捞 running 孤儿断点续跑（已 done 节点经
 * ctx.restoreNodeOutputs 跳过），终态走 TaskTerminalHandler 统一处理。
 * <p>waiting 实例不在此处理：唤醒监听器重启后自然重绑，超时由 MqWaitTimeoutScanner 兜底。
 * <p>防竞争：仅处理 updatedAt 早于 10 分钟前的实例——避开 MQ redelivery（消费中断重投）
 * 与本 boot 内 TaskRunnerListener 的重复执行窗口；单实例部署前提，多副本部署需引入
 * 分布式认领后再放宽。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InstanceRecoveryRunner {

    /** 孤儿判定阈值：updatedAt 早于该窗口的 running 实例才恢复（防与 redelivery 竞争） */
    private static final int STALE_MINUTES = 10;

    private final TaskInstanceRepository taskInstanceRepository;
    private final Executor executor;
    private final TaskTerminalHandler terminalHandler;

    @EventListener(ApplicationReadyEvent.class)
    public void recover() {
        LocalDateTime threshold = LocalDateTime.now().minusMinutes(STALE_MINUTES);
        List<TaskInstanceEntity> orphans = taskInstanceRepository
                .findByStatus(TaskInstanceEntity.ST_RUNNING).stream()
                .filter(i -> i.getUpdatedAt() == null || i.getUpdatedAt().isBefore(threshold))
                .toList();
        if (orphans.isEmpty()) {
            return;
        }
        log.warn("[orchestration] 重启恢复：发现 {} 个 running 孤儿实例，断点续跑",
                orphans.size());
        for (TaskInstanceEntity instance : orphans) {
            try {
                executor.run(instance);
                TaskInstanceEntity after = taskInstanceRepository.findById(instance.getId())
                        .orElse(instance);
                terminalHandler.handleTerminal(after);
                log.info("[orchestration] 孤儿实例 {} 恢复完成 status={} traceId={}",
                        instance.getId(), after.getStatus(), after.getTraceId());
            } catch (RuntimeException e) {
                log.warn("[orchestration] 孤儿实例 {} 恢复失败（保留现场人工排查）: {}",
                        instance.getId(), e.getMessage());
            }
        }
    }
}
