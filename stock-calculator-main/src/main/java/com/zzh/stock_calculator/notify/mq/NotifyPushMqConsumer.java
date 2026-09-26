package com.zzh.stock_calculator.notify.mq;

import com.zzh.stock_calculator.notify.service.PushCoalescingService;
import com.zzh.stockcalc.contract.MessageEnvelope;
import com.zzh.stockcalc.contract.MessageType;
import com.zzh.stockcalc.contract.MqPolicy;
import com.zzh.stockcalc.contract.MqQueue;
import com.zzh.stockcalc.contract.message.NotifyPushPayload;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * notify.push 消费者（docs/notify/design.md §4.2 N5：触达出口收敛一处）：
 * notify 服务组装后的通知 → 本消费者转合并窗口落地（PushCoalescingService 30s 去抖，
 * 同用户窗口内多条合成一条 Web Push；落库与逐订阅推送仍在 PushDeliveryService 全链——
 * 惰性清理/僵尸订阅处理同款）。
 * 装配条件与 PushCoalescingService 对齐（push.enabled=true），未来加邮件/webhook
 * 只扩本处消费者，notify 服务不直连用户。
 * <p>@Lazy(false)：豁免全局 lazy-initialization——无人注入的监听器 bean 若不强制
 * 实例化，@RabbitListener 端点永不注册（ClsArticleMqConsumer 同款）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@Lazy(false)
@ConditionalOnBean(PushCoalescingService.class)
public class NotifyPushMqConsumer {

    private final PushCoalescingService coalescingService;
    private final ObjectMapper objectMapper;

    @RabbitListener(queues = MqQueue.NOTIFY_PUSH)
    public void onMessage(MessageEnvelope envelope) {
        if (!MessageType.NOTIFY_PUSH.equals(envelope.getType())) {
            log.warn("[push] notify.push 队列收到未知类型消息 type={}，丢弃", envelope.getType());
            return;
        }
        NotifyPushPayload payload = objectMapper.convertValue(envelope.getPayload(),
                NotifyPushPayload.class);
        if (payload == null || payload.getUserId() == null || payload.getUserId().isEmpty()) {
            log.warn("[push] notify.push payload 缺 userId（messageId={}），丢弃",
                    envelope.getMessageId());
            return;
        }
        coalescingService.offer(payload.getUserId(), payload.getTitle(),
                payload.getBody(), payload.getUrl());
    }
}
