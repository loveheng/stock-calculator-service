package com.zzh.stock_calculator.notify.mq;

import com.zzh.stock_calculator.notify.entity.ReminderEntity;
import com.zzh.stock_calculator.notify.repository.ReminderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 解析损坏显式失败态回归（misc 散修 2026-09-25）：
 * trigger_spec / action JSON 损坏必须置 failed 终止调度，
 * 不得静默按 once 完结（误终止循环提醒）或误判 capability 方向。
 */
class ReminderRepeaterTest {

    private static final String SPEC_ONCE = "{\"nextFireAt\":\"2026-09-22T01:30:00Z\",\"repeat\":\"once\"}";

    private ReminderRepository repository;
    private ReminderSeeder seeder;
    private ReminderRepeater repeater;

    @BeforeEach
    void setUp() {
        repository = mock(ReminderRepository.class);
        seeder = mock(ReminderSeeder.class);
        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
        repeater = new ReminderRepeater(repository, seeder, rabbitTemplate, new ObjectMapper());
    }

    private ReminderEntity reminder(String triggerSpec, String action) {
        return ReminderEntity.builder()
                .id(1L)
                .triggerType("at_time")
                .status("active")
                .triggerSpec(triggerSpec)
                .action(action)
                .build();
    }

    @Test
    void corruptTriggerSpecMarksFailedAndSkipsComplete() {
        repeater.reseedOrComplete(reminder("not-json", "{\"kind\":\"text\",\"payload\":{}}"),
                OffsetDateTime.now());

        verify(repository).markFailed(1L);
        verify(repository, never()).markStatus(anyLong(), anyString());
        verify(seeder, never()).seed(anyLong(), any());
    }

    @Test
    void corruptActionMarksFailedAndSkipsComplete() {
        repeater.reseedOrComplete(reminder(SPEC_ONCE, "not-json"), OffsetDateTime.now());

        verify(repository).markFailed(1L);
        verify(repository, never()).markStatus(anyLong(), anyString());
    }

    @Test
    void onceTextActionStillCompletes() {
        repeater.reseedOrComplete(reminder(SPEC_ONCE, "{\"kind\":\"text\",\"payload\":{}}"),
                OffsetDateTime.now());

        verify(repository).markStatus(1L, "done");
        verify(repository, never()).markFailed(anyLong());
    }

    @Test
    void onceCapabilityActionDoesNotComplete() {
        repeater.reseedOrComplete(reminder(SPEC_ONCE,
                        "{\"kind\":\"capability\",\"payload\":{\"capabilityName\":\"x\"}}"),
                OffsetDateTime.now());

        verify(repository, never()).markStatus(anyLong(), anyString());
        verify(repository, never()).markFailed(anyLong());
    }

    @Test
    void dailyStillReseeds() {
        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
        when(rabbitTemplate.execute(any())).thenReturn(0L);
        repeater = new ReminderRepeater(repository, seeder, rabbitTemplate, new ObjectMapper());

        repeater.reseedOrComplete(reminder("{\"nextFireAt\":\"2026-09-22T01:30:00Z\",\"repeat\":\"daily\"}",
                "{\"kind\":\"text\",\"payload\":{}}"), OffsetDateTime.now());

        verify(seeder).seed(anyLong(), any());
        verify(repository).markStatus(1L, "active");
        verify(repository, never()).markFailed(anyLong());
    }
}
