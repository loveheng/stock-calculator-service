package com.zzh.stock_calculator.orchestration.mq;

import com.zzh.stock_calculator.orchestration.entity.TaskInstanceEntity;
import com.zzh.stock_calculator.orchestration.repository.TaskInstanceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * mq_wait 挂起超时扫描（步 6-2，§八 Zombie Task 防御兜底）：@Scheduled 扫
 * waiting 且 wait_deadline 已过的实例，置 timeout 终态——唤醒丢失（事件未达/
 * 服务重启丢监听）的挂起实例不会永久滞留。
 * <p>on_timeout 分支执行（如降级路径）随 DAG 语义后续接入；本步先保证状态机收口。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MqWaitTimeoutScanner {

    private final TaskInstanceRepository taskInstanceRepository;

    @Scheduled(fixedDelayString = "PT30S")
    @Transactional
    public void scan() {
        LocalDateTime now = LocalDateTime.now();
        taskInstanceRepository.findByStatus(TaskInstanceEntity.ST_WAITING).stream()
                .filter(i -> i.getWaitDeadline() != null && i.getWaitDeadline().isBefore(now))
                .forEach(instance -> {
                    instance.setStatus(TaskInstanceEntity.ST_TIMEOUT);
                    instance.setUpdatedAt(now);
                    taskInstanceRepository.save(instance);
                    log.warn("[orchestration] mq_wait 超时收口 instance={} traceId={} deadline={}",
                            instance.getId(), instance.getTraceId(), instance.getWaitDeadline());
                });
    }
}
