package com.zzh.stock_calculator.monitor;

import com.zzh.stock_calculator.monitor.entity.PullHeartbeatEntity;
import com.zzh.stock_calculator.monitor.repository.PullHeartbeatRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneOffset;

/**
 * 自循环心跳落表（docs/pull-loop-unification-design.md L4）：监听 crawler 消费端
 * 转发的 PullHeartbeatEvent，按 task_code upsert。观测信号非控制信号——写失败
 * 不重试不告警（下轮心跳 8min 后自然覆盖，设计不变量 3）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PullHeartbeatRecorder {

    private final PullHeartbeatRepository heartbeatRepository;

    @EventListener
    @Transactional
    public void onHeartbeat(PullHeartbeatEvent event) {
        try {
            PullHeartbeatEntity entity = heartbeatRepository.findById(event.getTaskCode())
                    .orElseGet(() -> PullHeartbeatEntity.builder()
                            .taskCode(event.getTaskCode())
                            .build());
            entity.setLastRenewTime(Instant.ofEpochMilli(event.getRenewedAt()).atOffset(ZoneOffset.UTC));
            entity.setDepth(event.getDepth());
            entity.setAppliedTtlMs(event.getAppliedTtlMs());
            heartbeatRepository.save(entity);
        } catch (Exception e) {
            log.warn("pull heartbeat 落表失败 taskCode={}（观测信号，忽略）: {}", event.getTaskCode(), e.toString());
        }
    }
}
