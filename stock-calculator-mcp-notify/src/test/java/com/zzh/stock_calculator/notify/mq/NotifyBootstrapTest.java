package com.zzh.stock_calculator.notify.mq;

import com.zzh.stock_calculator.notify.entity.ReminderEntity;
import com.zzh.stock_calculator.notify.repository.ReminderRepository;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 启动 bootstrap 解析损坏显式失败态回归（misc 散修 2026-09-25）：
 * spec 损坏必须置 failed 且不得让异常上抛炸掉 ApplicationRunner 启动。
 */
class NotifyBootstrapTest {

    @Test
    void corruptSpecMarksFailedWithoutCrashingStartup() {
        ReminderRepository repository = mock(ReminderRepository.class);
        ReminderSeeder seeder = mock(ReminderSeeder.class);
        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
        when(rabbitTemplate.execute(any())).thenReturn(0L);
        PlatformTransactionManager tm = mock(PlatformTransactionManager.class);
        when(tm.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        NotifyBootstrap bootstrap = new NotifyBootstrap(repository, seeder, rabbitTemplate,
                new ObjectMapper(), new TransactionTemplate(tm));

        ReminderEntity bad = ReminderEntity.builder()
                .id(9L)
                .triggerType("at_time")
                .status("active")
                .triggerSpec("{broken")
                .action("{}")
                .build();
        when(repository.findByStatusAndTriggerType("active", "at_time")).thenReturn(List.of(bad));

        assertDoesNotThrow(() -> bootstrap.run(null));

        verify(repository).markFailed(9L);
        verify(seeder, never()).seed(any(), any());
    }
}
