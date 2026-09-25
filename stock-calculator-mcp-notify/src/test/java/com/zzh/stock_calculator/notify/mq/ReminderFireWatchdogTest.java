package com.zzh.stock_calculator.notify.mq;

import com.zzh.stock_calculator.notify.entity.ReminderEntity;
import com.zzh.stock_calculator.notify.repository.ReminderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 漏种看门狗解析损坏显式失败态回归（misc 散修 2026-09-25）：
 * spec 损坏的 active 提醒必须置 failed 终止补种，不得每轮静默跳过形成永久漏触发。
 */
class ReminderFireWatchdogTest {

    private ReminderRepository repository;
    private ReminderSeeder seeder;
    private ReminderFireWatchdog watchdog;

    @BeforeEach
    void setUp() {
        repository = mock(ReminderRepository.class);
        seeder = mock(ReminderSeeder.class);
        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
        when(rabbitTemplate.execute(any())).thenReturn(0L);
        PlatformTransactionManager tm = mock(PlatformTransactionManager.class);
        when(tm.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        watchdog = new ReminderFireWatchdog(repository, seeder, rabbitTemplate,
                new ObjectMapper(), new TransactionTemplate(tm));
    }

    private ReminderEntity active(long id, String triggerSpec) {
        return ReminderEntity.builder()
                .id(id)
                .triggerType("at_time")
                .status("active")
                .triggerSpec(triggerSpec)
                .action("{\"kind\":\"text\",\"payload\":{}}")
                .build();
    }

    @Test
    void corruptSpecMarksFailedWithoutCrashing() {
        when(repository.findByStatusAndTriggerType("active", "at_time"))
                .thenReturn(List.of(active(7L, "not-json")));

        watchdog.checkAndReseed();

        verify(repository).markFailed(7L);
        verify(seeder, never()).seed(anyLong(), any());
    }

    @Test
    void validSpecStillReseeds() {
        when(repository.findByStatusAndTriggerType("active", "at_time"))
                .thenReturn(List.of(active(8L, "{\"nextFireAt\":\"2030-01-01T00:00:00Z\"}")));

        watchdog.checkAndReseed();

        verify(seeder).seed(anyLong(), any());
        verify(repository, never()).markFailed(anyLong());
    }

    @Test
    void corruptAndValidMixedOnlyCorruptMarked() {
        when(repository.findByStatusAndTriggerType("active", "at_time"))
                .thenReturn(List.of(active(7L, "not-json"),
                        active(8L, "{\"nextFireAt\":\"2030-01-01T00:00:00Z\"}")));

        watchdog.checkAndReseed();

        verify(repository).markFailed(7L);
        verify(seeder).seed(anyLong(), any());
    }
}
