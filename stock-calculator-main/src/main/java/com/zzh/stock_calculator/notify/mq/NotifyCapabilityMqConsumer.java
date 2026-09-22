package com.zzh.stock_calculator.notify.mq;

import com.zzh.stock_calculator.notify.NotifyCapabilityHandler;
import com.zzh.stockcalc.contract.MessageEnvelope;
import com.zzh.stockcalc.contract.MessageType;
import com.zzh.stockcalc.contract.MqExchange;
import com.zzh.stockcalc.contract.MqKey;
import com.zzh.stockcalc.contract.MqPolicy;
import com.zzh.stockcalc.contract.MqQueue;
import com.zzh.stockcalc.contract.message.NotifyCapabilityResult;
import com.zzh.stockcalc.contract.message.NotifyCapabilityTask;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * task.notify.capability 能力消费者（docs/notify/design.md §六）：
 * notify 能力请求 → 按 capabilityName 路由到 {@link NotifyCapabilityHandler} 实现 →
 * 执行结果沿 result.notify.capability 回流（traceId 原样带回，notify 据此关联 pending）。
 * 无匹配 handler / 执行异常均转 ok=false 回流（notify 走「数据暂不可用」降级，
 * 请求-回流两端状态机闭环，不丢请求不无限等待）。
 * <p>@Lazy(false)：豁免全局 lazy-initialization，防监听器端点静默失效（ClsArticleMqConsumer 同款）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@Lazy(false)
public class NotifyCapabilityMqConsumer {

    private final List<NotifyCapabilityHandler> handlers;
    private final ObjectMapper objectMapper;
    private final org.springframework.amqp.rabbit.core.RabbitTemplate rabbitTemplate;

    @RabbitListener(queues = MqQueue.TASK_NOTIFY_CAPABILITY)
    public void onTask(MessageEnvelope envelope) {
        if (!MessageType.TASK_NOTIFY_CAPABILITY.equals(envelope.getType())) {
            log.warn("[notify-cap] 能力队列收到未知类型 type={}，丢弃", envelope.getType());
            return;
        }
        NotifyCapabilityTask task = objectMapper.convertValue(envelope.getPayload(),
                NotifyCapabilityTask.class);
        if (task == null || task.getCapabilityName() == null || task.getReminderId() == null) {
            log.warn("[notify-cap] 能力请求缺 capabilityName/reminderId（messageId={}），回流失败",
                    envelope.getMessageId());
            reply(envelope, NotifyCapabilityResult.builder().ok(false)
                    .summary("能力请求格式非法").build());
            return;
        }

        NotifyCapabilityResult result;
        Map<String, NotifyCapabilityHandler> byName = handlers.stream()
                .collect(Collectors.toMap(NotifyCapabilityHandler::capability,
                        Function.identity(), (a, b) -> a));
        NotifyCapabilityHandler handler = byName.get(task.getCapabilityName());
        if (handler == null) {
            result = NotifyCapabilityResult.builder().ok(false)
                    .summary("能力未注册: " + task.getCapabilityName()).build();
        } else {
            try {
                result = handler.handle(task);
            } catch (Exception e) {
                log.error("[notify-cap] 能力执行异常 capability={}", task.getCapabilityName(), e);
                result = NotifyCapabilityResult.builder().ok(false)
                        .summary("能力执行失败: " + e.getMessage()).build();
            }
        }
        if (result.getReminderId() == null) {
            result.setReminderId(task.getReminderId());
        }
        reply(envelope, result);
    }

    /** 结果回流（traceId 原样带回，notify 按 traceId 关联 pending） */
    private void reply(MessageEnvelope request, NotifyCapabilityResult result) {
        MessageEnvelope envelope = MessageEnvelope.builder()
                .messageId(java.util.UUID.randomUUID().toString())
                .type(MqKey.RESULT_NOTIFY_CAPABILITY)
                .schemaVersion(MessageEnvelope.CURRENT_SCHEMA_VERSION)
                .occurredAt(System.currentTimeMillis())
                .traceId(request.getTraceId())
                .producer(MqPolicy.PRODUCER_MAIN)
                .payload(result)
                .build();
        rabbitTemplate.convertAndSend(MqExchange.RESULTS, MqKey.RESULT_NOTIFY_CAPABILITY, envelope);
        log.info("[notify-cap] 结果已回流 reminderId={} ok={} traceId={}",
                result.getReminderId(), result.isOk(), request.getTraceId());
    }
}
