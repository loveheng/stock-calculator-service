package com.zzh.stock_calculator.notify.mq;

import com.zzh.stock_calculator.notify.entity.CapabilityRequestEntity;
import com.zzh.stock_calculator.notify.entity.ReminderEntity;
import com.zzh.stock_calculator.notify.repository.ReminderRepository;
import com.zzh.stock_calculator.notify.service.CapabilityPendingService;
import com.zzh.stockcalc.contract.MqQueue;
import com.zzh.stockcalc.contract.message.NotifyCapabilityResult;
import com.zzh.stockcalc.contract.message.NotifyPushPayload;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * result.notify.capability 回流消费者（docs/notify/design.md §六）：
 * main 能力结果按 traceId 关联 pending 请求 → reminder 状态校验（done/cancelled 丢弃）→
 * 组装通知投 notify.push（ok=false 走降级文案「数据暂不可用」）。
 * 取走即删（resolve），重复回流第二次找不到 pending，天然幂等。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotifyCapabilityResultConsumer {

    private final CapabilityPendingService capabilityPendingService;
    private final ReminderRepository reminderRepository;
    private final NotifyPublisher notifyPublisher;
    private final ObjectMapper objectMapper;

    /** 消费全程一个事务：resolve 删除与 markStatus 完结均为 @Modifying 更新，缺事务抛 TransactionRequiredException */
    @org.springframework.transaction.annotation.Transactional
    @RabbitListener(queues = MqQueue.RESULT_NOTIFY_CAPABILITY)
    public void onResult(String envelopeJson) {
        com.zzh.stockcalc.contract.MessageEnvelope envelope;
        try {
            envelope = objectMapper.readValue(envelopeJson,
                    com.zzh.stockcalc.contract.MessageEnvelope.class);
        } catch (Exception e) {
            log.warn("[notify] capability 回流信封解析失败，丢弃", e);
            return;
        }
        NotifyCapabilityResult result = objectMapper.convertValue(envelope.getPayload(),
                NotifyCapabilityResult.class);
        if (result == null || envelope.getTraceId() == null) {
            log.warn("[notify] capability 回流缺 payload/traceId（messageId={}），丢弃",
                    envelope.getMessageId());
            return;
        }

        // 取走即删：回流只处理一次；找不到 pending = 已超时降级或重复回流，丢弃
        CapabilityRequestEntity pending = capabilityPendingService.resolve(envelope.getTraceId());
        if (pending == null) {
            log.info("[notify] capability 回流无在途请求 traceId={}（已降级或重复），丢弃",
                    envelope.getTraceId());
            return;
        }

        // reminder 状态校验（§五宕机恢复语义）：done/cancelled 则丢弃
        ReminderEntity reminder = reminderRepository.findById(pending.getReminderId()).orElse(null);
        if (reminder == null || !"active".equals(reminder.getStatus())) {
            log.info("[notify] capability 回流对应 reminder 非活跃 id={}，丢弃", pending.getReminderId());
            return;
        }

        String body = result.isOk()
                ? result.getSummary()
                : "数据暂不可用（能力请求超时或失败），稍后可重试";
        notifyPublisher.publishPush(NotifyPushPayload.builder()
                .userId(reminder.getUserId())
                .reminderId(reminder.getId())
                .title("快报")
                .body(body)
                .build(), envelope.getTraceId());
        // capability 型 once 提醒完结时点在回流投递后（fire 侧不完结，等结果组装）
        reminderRepository.markStatus(reminder.getId(), "done");
        log.info("[notify] capability 结果已组装投递 reminderId={} ok={} traceId={}",
                reminder.getId(), result.isOk(), envelope.getTraceId());
    }
}
