package com.zzh.stock_calculator.notify.mq;

import com.zzh.stockcalc.contract.MqExchange;
import com.zzh.stockcalc.contract.MqKey;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 定时提醒投种器（docs/notify/design.md §五 at_time 自循环）：
 * 种子发 reminder.delay.q，per-message expiration = 距 next_fire_at 毫秒数；
 * 到期死信经 DLX 改写（DLK=reminder.fire）进 fire 队列，notify 消费触发。
 * 「钟」在 broker 不在进程（N2）：种子不回收，取消/重建后的残留种子由 fire 消费幂等挡。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReminderSeeder {

    private final RabbitTemplate rabbitTemplate;

    /** 投一颗种子：payload 为 reminder_id，到期后 fire 消费按 id 回库判重执行 */
    public void seed(Long reminderId, OffsetDateTime nextFireAt) {
        long ttlMs = Math.max(0, nextFireAt.toInstant().toEpochMilli() - System.currentTimeMillis());
        MessageProperties props = new MessageProperties();
        props.setExpiration(Long.toString(ttlMs));
        props.setMessageId(UUID.randomUUID().toString());
        Message message = new Message(
                reminderId.toString().getBytes(StandardCharsets.UTF_8), props);
        rabbitTemplate.send(MqExchange.TASKS, MqKey.REMINDER_DELAY, message);
        log.info("[notify] reminder seeded id={} ttlMs={}", reminderId, ttlMs);
    }
}
