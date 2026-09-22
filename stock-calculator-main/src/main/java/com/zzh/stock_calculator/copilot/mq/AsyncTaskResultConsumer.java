package com.zzh.stock_calculator.copilot.mq;

import com.zzh.stockcalc.contract.MqKey;
import com.zzh.stockcalc.contract.MqQueue;
import com.zzh.stockcalc.contract.MessageEnvelope;
import com.zzh.stock_calculator.copilot.service.AsyncTaskChannelService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 异步任务终态事件消费者（步 6-3b）：main 独占消费 async.task.result.q
 * （绑定 task.completed.* / task.failed.*，与 orchestration 唤醒队列各自独立绑定互不抢消息）。
 * 按 payload.correlation_id 查 user_async_task_log 还原 user_id → 审计终态回写 → SSE 推送。
 * <p>@Lazy(false)：豁免全局 lazy-initialization——无人注入的监听器 bean 若不强制
 * 实例化，@RabbitListener 端点永不注册（ClsArticleMqConsumer 同款）。</p>
 */
@Slf4j
@Component
@Lazy(false)
@RequiredArgsConstructor
public class AsyncTaskResultConsumer {

    private final AsyncTaskChannelService asyncTaskChannelService;
    private final ObjectMapper objectMapper;

    @RabbitListener(bindings = @org.springframework.amqp.rabbit.annotation.QueueBinding(
            value = @org.springframework.amqp.rabbit.annotation.Queue(
                    value = MqQueue.ASYNC_TASK_RESULT,
                    durable = "true",
                    arguments = @org.springframework.amqp.rabbit.annotation.Argument(
                            name = "x-expires", value = "86400000", type = "java.lang.Long")),
            exchange = @org.springframework.amqp.rabbit.annotation.Exchange(
                    value = "stockcalc.tasks", type = "topic"),
            key = {"task.completed.*", "task.failed.*"}))
    public void onTaskResult(String body) {
        MessageEnvelope envelope;
        try {
            envelope = objectMapper.readValue(body, MessageEnvelope.class);
        } catch (RuntimeException e) {
            log.error("[async-channel] 信封解析失败，丢弃: {}", e.getMessage());
            return;
        }
        String routing = envelope.getType();
        boolean failed = routing != null && routing.startsWith(MqKey.TASK_FAILED_PREFIX);
        JsonNode payload = objectMapper.valueToTree(envelope.getPayload());
        String correlationId = payload.path("correlation_id").asText("");
        if (correlationId.isBlank()) {
            log.warn("[async-channel] 终态事件缺 correlation_id，忽略: routing={} traceId={}",
                    routing, envelope.getTraceId());
            return;
        }
        // 映射回写（CAS 防乱序覆盖终态）+ SSE 推送（finalizeTask 内接线）
        String status = failed ? "FAILED" : "DONE";
        asyncTaskChannelService.finalizeTask(correlationId, status, payload);
        log.info("[async-channel] 终态事件已处理 correlationId={} status={} traceId={}",
                correlationId, status, envelope.getTraceId());
    }
}
