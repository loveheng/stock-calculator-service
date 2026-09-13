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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PullLoopWatchdogTask 单测（docs/pull-loop-unification-design.md §3.4 / L6）：
 * 配置快照周期性重推、心跳超期补种、进程内补种节流、enabled=false 不补种。
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

    // ==================== 日历任务认领（docs/pull-loop-unification-design.md §8.3.2） ====================

    @Test
    @DisplayName("CALENDAR 游标 NULL：初始化为下一日历点，不触发执行（crontab 语义）")
    void nullCursorInitializesWithoutDispatch() {
        when(configRepository.findByScheduleMode(PullTaskConfigEntity.MODE_CALENDAR))
                .thenReturn(List.of(calendarConfig(MqKey.TASK_HELLO_WORLD, true, null)));

        watchdog.calendarClaim();

        ArgumentCaptor<OffsetDateTime> next = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(configRepository).initCalendarCursor(eq(MqKey.TASK_HELLO_WORLD), next.capture(), any(OffsetDateTime.class));
        assertTrue(next.getValue().toInstant().toEpochMilli() > System.currentTimeMillis());
        verify(dispatchPort, never()).dispatchCalendarTask(anyString());
    }

    @Test
    @DisplayName("CALENDAR 逾期游标：CAS 认领成功 → 直发一次性任务")
    void overdueCursorClaimsAndDispatches() {
        when(configRepository.findByScheduleMode(PullTaskConfigEntity.MODE_CALENDAR))
                .thenReturn(List.of(calendarConfig(MqKey.TASK_HELLO_WORLD, true, OffsetDateTime.now().minusHours(1))));
        when(configRepository.claimCalendarSlot(eq(MqKey.TASK_HELLO_WORLD), any(OffsetDateTime.class), any(OffsetDateTime.class)))
                .thenReturn(1);

        watchdog.calendarClaim();

        verify(dispatchPort).dispatchCalendarTask(MqKey.TASK_HELLO_WORLD);
    }

    @Test
    @DisplayName("CALENDAR 认领后投递失败：游标回滚保持逾期，下轮重认领（L12）")
    void dispatchFailureRollsBackCursor() {
        when(configRepository.findByScheduleMode(PullTaskConfigEntity.MODE_CALENDAR))
                .thenReturn(List.of(calendarConfig(MqKey.TASK_HELLO_WORLD, true, OffsetDateTime.now().minusHours(1))));
        when(configRepository.claimCalendarSlot(eq(MqKey.TASK_HELLO_WORLD), any(OffsetDateTime.class), any(OffsetDateTime.class)))
                .thenReturn(1);
        doThrow(new RuntimeException("mq down")).when(dispatchPort).dispatchCalendarTask(anyString());

        watchdog.calendarClaim();

        verify(configRepository).rollbackCalendarCursor(eq(MqKey.TASK_HELLO_WORLD), any(OffsetDateTime.class), any(OffsetDateTime.class));
    }

    @Test
    @DisplayName("CALENDAR 认领竞争失败（affected=0）：不投递")
    void lostClaimRaceSkipsDispatch() {
        when(configRepository.findByScheduleMode(PullTaskConfigEntity.MODE_CALENDAR))
                .thenReturn(List.of(calendarConfig(MqKey.TASK_HELLO_WORLD, true, OffsetDateTime.now().minusHours(1))));
        when(configRepository.claimCalendarSlot(eq(MqKey.TASK_HELLO_WORLD), any(OffsetDateTime.class), any(OffsetDateTime.class)))
                .thenReturn(0);

        watchdog.calendarClaim();

        verify(dispatchPort, never()).dispatchCalendarTask(anyString());
    }

    @Test
    @DisplayName("CALENDAR 未到期游标 / 停用行：跳过")
    void futureCursorAndDisabledRowSkip() {
        when(configRepository.findByScheduleMode(PullTaskConfigEntity.MODE_CALENDAR))
                .thenReturn(List.of(
                        calendarConfig(MqKey.TASK_HELLO_WORLD, true, OffsetDateTime.now().plusHours(1)),
                        calendarConfig("task.other", false, OffsetDateTime.now().minusHours(1))));

        watchdog.calendarClaim();

        verify(dispatchPort, never()).dispatchCalendarTask(anyString());
        verify(configRepository, never()).initCalendarCursor(anyString(), any(OffsetDateTime.class), any(OffsetDateTime.class));
    }

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
