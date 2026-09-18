package com.zzh.stock_calculator.monitor;

import com.zzh.stock_calculator.monitor.entity.PullHeartbeatEntity;
import com.zzh.stock_calculator.monitor.entity.PullTaskConfigEntity;
import com.zzh.stock_calculator.monitor.repository.PullHeartbeatRepository;
import com.zzh.stock_calculator.monitor.repository.PullTaskConfigRepository;
import com.zzh.stockcalc.contract.MqKey;
import com.zzh.stockcalc.contract.message.PullConfigPayload;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PullLoopWatchdogTask 单测（docs/architecture/pull-loop-unification.md §3.4 / L6）：
 * 配置快照周期性重推、心跳超期补种、进程内补种节流、enabled=false 不补种、
 * CALENDAR 行隔离（不补种/不进快照）；CALENDAR 认领场景见 CalendarTaskClaimSchedulerTest。
 */
@ExtendWith(MockitoExtension.class)
class PullLoopWatchdogTaskTest {

    @Mock
    private PullTaskConfigRepository configRepository;
    @Mock
    private PullHeartbeatRepository heartbeatRepository;
    @Mock
    private PullLoopDispatchPort dispatchPort;

    private PullLoopWatchdogTask watchdog;

    @BeforeEach
    void setUp() {
        watchdog = new PullLoopWatchdogTask(configRepository, heartbeatRepository, dispatchPort);
    }

    private PullTaskConfigEntity config(String taskCode, boolean enabled, long ttlMs) {
        return PullTaskConfigEntity.builder().taskCode(taskCode).enabled(enabled).ttlMs(ttlMs).build();
    }

    /** §8 CALENDAR 行构造（每日 07:00 Asia/Shanghai，首个接入任务 task.hello.world） */
    private PullTaskConfigEntity calendarConfig(String taskCode, boolean enabled, OffsetDateTime cursor) {
        return PullTaskConfigEntity.builder()
                .taskCode(taskCode)
                .enabled(enabled)
                .ttlMs(0)
                .scheduleMode(PullTaskConfigEntity.MODE_CALENDAR)
                .cronExpression("0 0 7 * * *")
                .timezone("Asia/Shanghai")
                .nextExpectedTime(cursor)
                .build();
    }

    private PullHeartbeatEntity heartbeatRecent(String taskCode, long ttlMs) {
        return PullHeartbeatEntity.builder()
                .taskCode(taskCode)
                .lastRenewTime(OffsetDateTime.now().minusSeconds(60))
                .depth(0)
                .appliedTtlMs(ttlMs)
                .build();
    }

    @Test
    @DisplayName("心跳新鲜：只推配置不补种")
    void freshHeartbeatSkipsReseed() {
        when(configRepository.findAll()).thenReturn(List.of(
                config(MqKey.TASK_CLS_PULL, true, 480_000L)));
        when(heartbeatRepository.findById(MqKey.TASK_CLS_PULL))
                .thenReturn(Optional.of(heartbeatRecent(MqKey.TASK_CLS_PULL, 480_000L)));

        watchdog.watch();

        verify(dispatchPort, never()).dispatchSeed(anyString(), eq(480_000L));
        ArgumentCaptor<PullConfigPayload> payload = ArgumentCaptor.forClass(PullConfigPayload.class);
        verify(dispatchPort).pushConfig(payload.capture());
        assertEquals(1, payload.getValue().getTasks().size());
        assertTrue(payload.getValue().getTasks().get(0).isEnabled());
    }

    @Test
    @DisplayName("心跳超期（无心跳行）→ 补种一次；周期内再次 watch 被节流")
    void staleHeartbeatReseedsWithThrottle() {
        when(configRepository.findAll()).thenReturn(List.of(
                config(MqKey.TASK_CLS_PULL, true, 480_000L)));
        when(heartbeatRepository.findById(MqKey.TASK_CLS_PULL)).thenReturn(Optional.empty());

        watchdog.watch();
        watchdog.watch();

        verify(dispatchPort).dispatchSeed(MqKey.TASK_CLS_PULL_DELAY, 480_000L);
    }

    @Test
    @DisplayName("enabled=false：不补种，但配置快照照推（停用语义广播）")
    void disabledConfigNeverReseeds() {
        when(configRepository.findAll()).thenReturn(List.of(
                config(MqKey.TASK_CLS_PULL, false, 480_000L)));

        watchdog.watch();

        verify(dispatchPort, never()).dispatchSeed(anyString(), org.mockito.ArgumentMatchers.anyLong());
        verify(dispatchPort).pushConfig(any(PullConfigPayload.class));
    }

    // CALENDAR 认领协议场景已随 CalendarTaskClaimScheduler 拆分迁至 CalendarTaskClaimSchedulerTest

    @Test
    @DisplayName("watch() 对 CALENDAR 行不补种、快照剔除（不变量 7 / L10）")
    void watchSkipsCalendarRowsAndExcludesFromSnapshot() {
        when(configRepository.findAll()).thenReturn(List.of(
                calendarConfig(MqKey.TASK_HELLO_WORLD, true, OffsetDateTime.now().minusHours(1)),
                config(MqKey.TASK_CLS_PULL, true, 480_000L)));
        when(heartbeatRepository.findById(MqKey.TASK_CLS_PULL))
                .thenReturn(Optional.of(heartbeatRecent(MqKey.TASK_CLS_PULL, 480_000L)));

        watchdog.watch();

        verify(dispatchPort, never()).dispatchSeed(anyString(), anyLong());
        verify(dispatchPort, never()).dispatchCalendarTask(anyString());
        ArgumentCaptor<PullConfigPayload> payload = ArgumentCaptor.forClass(PullConfigPayload.class);
        verify(dispatchPort).pushConfig(payload.capture());
        assertEquals(1, payload.getValue().getTasks().size());
        assertEquals(MqKey.TASK_CLS_PULL, payload.getValue().getTasks().get(0).getTaskCode());
    }
}
