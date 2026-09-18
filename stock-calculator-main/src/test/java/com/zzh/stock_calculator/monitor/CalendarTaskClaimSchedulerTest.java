package com.zzh.stock_calculator.monitor;

import com.zzh.stock_calculator.monitor.entity.PullTaskConfigEntity;
import com.zzh.stock_calculator.monitor.repository.PullTaskConfigRepository;
import com.zzh.stockcalc.contract.MqKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CalendarTaskClaimScheduler 单测（docs/architecture/pull-loop-unification.md §8.3.2/§10，
 * 任务管理统一合表版）：同一认领循环双分发——task.* 未命中注册表 → MQ 投递；
 * job.* 命中 → 进程内 handler.run()。CAS 认领协议全覆盖：游标初始化不触发、
 * 逾期认领、失败回滚、竞争失败跳过、未到期/停用跳过、app-task.enabled 只压制
 * 进程内半区（MQ 型不受影响）；启动校验 fail-fast + 双向 warn。
 */
@ExtendWith(MockitoExtension.class)
class CalendarTaskClaimSchedulerTest {

    @Mock
    private PullTaskConfigRepository configRepository;
    @Mock
    private PullLoopDispatchPort dispatchPort;
    @Mock
    private AppTaskHandler handler;

    private CalendarTaskClaimScheduler scheduler;

    @BeforeEach
    void setUp() {
        lenient().when(handler.taskCode()).thenReturn("job.test");
        scheduler = new CalendarTaskClaimScheduler(configRepository, dispatchPort, List.of(handler), true);
    }

    /** §8 CALENDAR 行构造（每日 07:00 Asia/Shanghai；MQ 型 task.* 与进程内 job.* 共用） */
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

    /** 先以空表 stub 构建注册表（validate），再 re-stub 认领快照（Mockito 后置覆盖生效） */
    private void seedRegistryThenRows(List<PullTaskConfigEntity> rows) {
        when(configRepository.findByScheduleMode(PullTaskConfigEntity.MODE_CALENDAR)).thenReturn(List.of());
        scheduler.validate();
        when(configRepository.findByScheduleMode(PullTaskConfigEntity.MODE_CALENDAR)).thenReturn(rows);
    }

    // ==================== MQ 投递半区（task.*，注册表未命中） ====================

    @Test
    @DisplayName("CALENDAR 游标 NULL：初始化为下一日历点，不触发投递（crontab 语义）")
    void nullCursorInitializesWithoutDispatch() {
        when(configRepository.findByScheduleMode(PullTaskConfigEntity.MODE_CALENDAR))
                .thenReturn(List.of(calendarConfig(MqKey.TASK_HELLO_WORLD, true, null)));

        scheduler.calendarClaim();

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

        scheduler.calendarClaim();

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

        scheduler.calendarClaim();

        verify(configRepository).rollbackCalendarCursor(eq(MqKey.TASK_HELLO_WORLD), any(OffsetDateTime.class), any(OffsetDateTime.class));
    }

    @Test
    @DisplayName("CALENDAR 认领竞争失败（affected=0）：不投递")
    void lostClaimRaceSkipsDispatch() {
        when(configRepository.findByScheduleMode(PullTaskConfigEntity.MODE_CALENDAR))
                .thenReturn(List.of(calendarConfig(MqKey.TASK_HELLO_WORLD, true, OffsetDateTime.now().minusHours(1))));
        when(configRepository.claimCalendarSlot(eq(MqKey.TASK_HELLO_WORLD), any(OffsetDateTime.class), any(OffsetDateTime.class)))
                .thenReturn(0);

        scheduler.calendarClaim();

        verify(dispatchPort, never()).dispatchCalendarTask(anyString());
    }

    @Test
    @DisplayName("CALENDAR 未到期游标 / 停用行：跳过")
    void futureCursorAndDisabledRowSkip() {
        when(configRepository.findByScheduleMode(PullTaskConfigEntity.MODE_CALENDAR))
                .thenReturn(List.of(
                        calendarConfig(MqKey.TASK_HELLO_WORLD, true, OffsetDateTime.now().plusHours(1)),
                        calendarConfig("task.other", false, OffsetDateTime.now().minusHours(1))));

        scheduler.calendarClaim();

        verify(dispatchPort, never()).dispatchCalendarTask(anyString());
        verify(configRepository, never()).initCalendarCursor(anyString(), any(OffsetDateTime.class), any(OffsetDateTime.class));
    }

    // ==================== 进程内半区（job.*，注册表命中） ====================

    @Test
    @DisplayName("job 游标 NULL：初始化为下一日历点，不触发 handler（crontab 语义）")
    void jobNullCursorInitializesWithoutRun() {
        seedRegistryThenRows(List.of(calendarConfig("job.test", true, null)));

        scheduler.calendarClaim();

        ArgumentCaptor<OffsetDateTime> next = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(configRepository).initCalendarCursor(eq("job.test"), next.capture(), any(OffsetDateTime.class));
        assertTrue(next.getValue().toInstant().toEpochMilli() > System.currentTimeMillis());
        verify(handler, never()).run();
    }

    @Test
    @DisplayName("job 逾期游标：CAS 认领成功 → 进程内 handler.run()（不走 MQ）")
    void jobOverdueClaimRunsHandlerInProcess() {
        seedRegistryThenRows(List.of(calendarConfig("job.test", true, OffsetDateTime.now().minusHours(1))));
        when(configRepository.claimCalendarSlot(eq("job.test"), any(OffsetDateTime.class), any(OffsetDateTime.class)))
                .thenReturn(1);

        scheduler.calendarClaim();

        verify(handler).run();
        verify(dispatchPort, never()).dispatchCalendarTask(anyString());
    }

    @Test
    @DisplayName("job 认领后 handler 抛异常：游标回滚保持逾期，下轮重认领（L12）")
    void jobRunFailureRollsBackCursor() {
        seedRegistryThenRows(List.of(calendarConfig("job.test", true, OffsetDateTime.now().minusHours(1))));
        when(configRepository.claimCalendarSlot(eq("job.test"), any(OffsetDateTime.class), any(OffsetDateTime.class)))
                .thenReturn(1);
        doThrow(new RuntimeException("boom")).when(handler).run();

        scheduler.calendarClaim();

        verify(configRepository).rollbackCalendarCursor(eq("job.test"), any(OffsetDateTime.class), any(OffsetDateTime.class));
    }

    @Test
    @DisplayName("job.* 行未注册 handler：静默跳过（不执行、不改道 MQ）")
    void jobUnregisteredRowSkipsSilently() {
        seedRegistryThenRows(List.of(calendarConfig("job.orphan", true, OffsetDateTime.now().minusHours(1))));

        scheduler.calendarClaim();

        verify(handler, never()).run();
        verify(dispatchPort, never()).dispatchCalendarTask(anyString());
        verify(configRepository, never()).initCalendarCursor(anyString(), any(OffsetDateTime.class), any(OffsetDateTime.class));
        verify(configRepository, never()).claimCalendarSlot(anyString(), any(OffsetDateTime.class), any(OffsetDateTime.class));
    }

    @Test
    @DisplayName("app-task.enabled=false：进程内 job.* 压制，MQ 型 task.* 照常投递（E2E 契约）")
    void appTaskDisabledSuppressesInProcessOnly() {
        CalendarTaskClaimScheduler disabled = new CalendarTaskClaimScheduler(
                configRepository, dispatchPort, List.of(handler), false);
        when(configRepository.findByScheduleMode(PullTaskConfigEntity.MODE_CALENDAR)).thenReturn(List.of());
        disabled.validate();
        when(configRepository.findByScheduleMode(PullTaskConfigEntity.MODE_CALENDAR))
                .thenReturn(List.of(
                        calendarConfig("job.test", true, OffsetDateTime.now().minusHours(1)),
                        calendarConfig(MqKey.TASK_HELLO_WORLD, true, OffsetDateTime.now().minusHours(1))));
        when(configRepository.claimCalendarSlot(eq(MqKey.TASK_HELLO_WORLD), any(OffsetDateTime.class), any(OffsetDateTime.class)))
                .thenReturn(1);

        disabled.calendarClaim();

        verify(handler, never()).run();
        verify(configRepository, never()).claimCalendarSlot(eq("job.test"), any(OffsetDateTime.class), any(OffsetDateTime.class));
        verify(dispatchPort).dispatchCalendarTask(MqKey.TASK_HELLO_WORLD);
    }

    // ==================== 启动校验（fail-fast + 双向 warn） ====================

    @Test
    @DisplayName("启动校验：cron 为空的 CALENDAR 行 fail-fast 阻止启动")
    void blankCronFailsFast() {
        when(configRepository.findByScheduleMode(PullTaskConfigEntity.MODE_CALENDAR))
                .thenReturn(List.of(PullTaskConfigEntity.builder()
                        .taskCode(MqKey.TASK_HELLO_WORLD)
                        .scheduleMode(PullTaskConfigEntity.MODE_CALENDAR)
                        .cronExpression(" ")
                        .build()));

        assertThrows(IllegalStateException.class, () -> scheduler.validate());
    }

    @Test
    @DisplayName("启动校验：cron 不可解析 fail-fast")
    void unparseableCronFailsFast() {
        when(configRepository.findByScheduleMode(PullTaskConfigEntity.MODE_CALENDAR))
                .thenReturn(List.of(PullTaskConfigEntity.builder()
                        .taskCode(MqKey.TASK_HELLO_WORLD)
                        .scheduleMode(PullTaskConfigEntity.MODE_CALENDAR)
                        .cronExpression("not a cron")
                        .build()));

        assertThrows(IllegalArgumentException.class, () -> scheduler.validate());
    }

    @Test
    @DisplayName("启动校验：全部合法 CALENDAR 行放行不抛")
    void validRowsPassStartupValidation() {
        when(configRepository.findByScheduleMode(PullTaskConfigEntity.MODE_CALENDAR))
                .thenReturn(List.of(calendarConfig(MqKey.TASK_HELLO_WORLD, true, null)));

        scheduler.validate();

        verify(configRepository).findByScheduleMode(PullTaskConfigEntity.MODE_CALENDAR);
    }

    @Test
    @DisplayName("启动校验：handler taskCode 重复 fail-fast")
    void validateRejectsDuplicateTaskCode() {
        AppTaskHandler duplicate = mock(AppTaskHandler.class);
        when(duplicate.taskCode()).thenReturn("job.test");
        CalendarTaskClaimScheduler dupScheduler = new CalendarTaskClaimScheduler(
                configRepository, dispatchPort, List.of(handler, duplicate), true);

        assertThrows(IllegalStateException.class, () -> dupScheduler.validate());
    }

    @Test
    @DisplayName("启动校验：job.* 行无对应 handler 只 warn 不阻断")
    void jobRowWithoutHandlerWarnsButDoesNotThrow() {
        when(configRepository.findByScheduleMode(PullTaskConfigEntity.MODE_CALENDAR))
                .thenReturn(List.of(calendarConfig("job.orphan", true, null)));

        assertDoesNotThrow(() -> scheduler.validate());
    }
}
