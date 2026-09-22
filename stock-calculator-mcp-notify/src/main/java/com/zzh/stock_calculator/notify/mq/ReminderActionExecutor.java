package com.zzh.stock_calculator.notify.mq;

import com.zzh.stock_calculator.notify.entity.ReminderEntity;
import com.zzh.stock_calculator.notify.service.CapabilityPendingService;
import com.zzh.stockcalc.contract.message.NotifyCapabilityTask;
import com.zzh.stockcalc.contract.message.NotifyPushPayload;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 动作执行器（docs/notify/design.md §六）：fire（at_time 到点）与 event（on_event 命中）
 * 共用的 action 分发出口——text 直达组装通知投 notify.push；capability 发能力请求 +
 * 登记 pending（deadline 到未回流由 CapabilityWatchdog 降级，不做无限等待）。
 * action JSON 非法时降级文本通知（提醒不因配置错误静默丢失）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReminderActionExecutor {

    private final NotifyPublisher notifyPublisher;
    private final CapabilityPendingService capabilityPendingService;
    private final ObjectMapper objectMapper;

    /** 执行 reminder 的 action（fire 与 event 消费者共用） */
    public void execute(ReminderEntity reminder) {
        JsonNode action;
        try {
            action = objectMapper.readTree(reminder.getAction());
        } catch (Exception e) {
            log.error("[notify] reminder id={} action JSON 非法，降级文本通知", reminder.getId(), e);
            action = objectMapper.createObjectNode().put("kind", "text");
        }

        String traceId = UUID.randomUUID().toString();
        String kind = action.path("kind").asText("text");
        JsonNode payload = action.path("payload");

        if ("capability".equals(kind)) {
            Map<String, Object> params = new LinkedHashMap<>();
            payload.properties().forEach(entry -> params.put(entry.getKey(), entry.getValue()));
            // §六：临时关联 + 超时兜底——登记 pending（deadline 默认 60s）
            notifyPublisher.publishCapabilityTask(NotifyCapabilityTask.builder()
                    .reminderId(reminder.getId())
                    .capabilityName(payload.path("capabilityName").asText())
                    .params(params)
                    .build(), traceId);
            capabilityPendingService.register(traceId, reminder.getId());
            return;
        }

        // text 直达：零额外跳数，模板轻量拼接
        notifyPublisher.publishPush(NotifyPushPayload.builder()
                .userId(reminder.getUserId())
                .reminderId(reminder.getId())
                .title(payload.path("title").asText("提醒"))
                .body(payload.path("body").asText("到点啦（reminder_id=" + reminder.getId() + "）"))
                .url(payload.path("url").asText(null))
                .build(), traceId);
        log.info("[notify] reminder id={} text 通知已投递 traceId={}", reminder.getId(), traceId);
    }
}
