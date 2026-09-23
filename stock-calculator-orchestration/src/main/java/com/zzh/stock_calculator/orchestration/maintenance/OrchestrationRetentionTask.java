package com.zzh.stock_calculator.orchestration.maintenance;

import com.zzh.stock_calculator.orchestration.entity.TaskInstanceEntity;
import com.zzh.stock_calculator.orchestration.repository.DomainEventInboxRepository;
import com.zzh.stock_calculator.orchestration.repository.MatchLogRepository;
import com.zzh.stock_calculator.orchestration.repository.TaskInstanceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 编排器数据保留清理（match_log/task_instance 保留策略债收口）：
 * <ul>
 *   <li>match_log：原 append-only（仅文档约定量大后再管），改每日滚动删除（默认 30 天）；</li>
 *   <li>task_instance：终态实例（done/failed/timeout）到期删除（默认 90 天），waiting/running 不动；</li>
 *   <li>domain_event_inbox：未被重放消费的过期缓冲（默认 7 天，对齐事件时效）。</li>
 * </ul>
 * 口径经 orchestration.retention.* 配置调整；enabled=false 整体停用。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrchestrationRetentionTask {

    private final MatchLogRepository matchLogRepository;
    private final TaskInstanceRepository taskInstanceRepository;
    private final DomainEventInboxRepository inboxRepository;

    @Value("${orchestration.retention.enabled:true}")
    private boolean enabled;

    @Value("${orchestration.retention.match-log-days:30}")
    private int matchLogDays;

    @Value("${orchestration.retention.task-instance-days:90}")
    private int taskInstanceDays;

    @Value("${orchestration.retention.event-inbox-days:7}")
    private int eventInboxDays;

    @Scheduled(cron = "${orchestration.retention.cron:0 30 3 * * *}")
    @Transactional
    public void cleanup() {
        if (!enabled) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        long matchLog = matchLogRepository.deleteByCreatedAtBefore(now.minusDays(matchLogDays));
        long instances = taskInstanceRepository.deleteByStatusInAndUpdatedAtBefore(
                List.of(TaskInstanceEntity.ST_DONE, TaskInstanceEntity.ST_FAILED, TaskInstanceEntity.ST_TIMEOUT),
                now.minusDays(taskInstanceDays));
        long inbox = inboxRepository.deleteByCreatedAtBefore(now.minusDays(eventInboxDays));
        if (matchLog + instances + inbox > 0) {
            log.info("[orchestration] 保留清理完成: match_log={} task_instance={} event_inbox={}",
                    matchLog, instances, inbox);
        }
    }
}
