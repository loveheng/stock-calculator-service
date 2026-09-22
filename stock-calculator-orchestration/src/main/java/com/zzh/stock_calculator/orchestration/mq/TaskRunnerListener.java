package com.zzh.stock_calculator.orchestration.mq;

import com.zzh.stock_calculator.orchestration.entity.TaskInstanceEntity;
import com.zzh.stock_calculator.orchestration.executor.Executor;
import com.zzh.stock_calculator.orchestration.repository.TaskInstanceRepository;
import com.zzh.stockcalc.contract.MqKey;
import com.zzh.stockcalc.contract.MessageEnvelope;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 任务启动请求消费者（步 6-3a 真异步核心）：dispatch/create_task 即刻返回 RUNNING 后，
 * 执行主体在此（MQ 消费侧）调 Executor.run——HTTP 请求线程彻底脱离长任务阻塞。
 * <p>幂等：实例非 running（done/failed/waiting/timeout）时忽略重复投递（messageId=traceId
 * 重放防重）；run() 内 advisory lock 同 plan 串行。
 * <p>终态事件（#3）：run() 返回后按实例终态回发 task.completed./task.failed.，
 * 供 mq_wait 唤醒与 main 侧 SSE/GC 消费。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaskRunnerListener {

    private final TaskInstanceRepository taskInstanceRepository;
    private final Executor executor;
    private final TaskMessageSender taskMessageSender;
    private final ObjectMapper om = new ObjectMapper();

    @RabbitListener(bindings = @org.springframework.amqp.rabbit.annotation.QueueBinding(
            value = @org.springframework.amqp.rabbit.annotation.Queue(
                    value = "orchestration.task.run.q",
                    durable = "true",
                    arguments = @org.springframework.amqp.rabbit.annotation.Argument(
                            name = "x-expires", value = "86400000", type = "java.lang.Long")),
            exchange = @org.springframework.amqp.rabbit.annotation.Exchange(
                    value = TaskRunnerExchangeConst.TASKS, type = "topic"),
            key = MqKey.TASK_ORCHESTRATION_RUN))
    public void onRunRequest(String body) {
        MessageEnvelope envelope = parse(body);
        JsonNode payload = om.valueToTree(envelope.getPayload());
        long taskId = payload.path("task_id").asLong(0);
        if (taskId <= 0) {
            log.warn("[orchestration] 启动请求缺 task_id，忽略: traceId={}", envelope.getTraceId());
            return;
        }
        TaskInstanceEntity instance = taskInstanceRepository.findById(taskId).orElse(null);
        if (instance == null) {
            log.warn("[orchestration] 启动请求对应实例不存在 taskId={} traceId={}", taskId, envelope.getTraceId());
            return;
        }
        if (!TaskInstanceEntity.ST_RUNNING.equals(instance.getStatus())) {
            // 重放/重复投递：非初始态实例不重跑（幂等）
            log.info("[orchestration] 实例 {} 状态 {} 非初始，忽略重复启动请求 traceId={}",
                    taskId, instance.getStatus(), instance.getTraceId());
            return;
        }
        executor.run(instance);
        TaskInstanceEntity after = taskInstanceRepository.findById(taskId).orElse(instance);
        String finalStatus = after.getStatus();
        // 终态事件回发（mq_wait 唤醒 + main SSE/GC 数据源）；waiting 不发（等 mq_wait 结果事件）
        if (TaskInstanceEntity.ST_DONE.equals(finalStatus) || TaskInstanceEntity.ST_FAILED.equals(finalStatus)) {
            String routing = (TaskInstanceEntity.ST_DONE.equals(finalStatus)
                    ? MqKey.TASK_COMPLETED_PREFIX : MqKey.TASK_FAILED_PREFIX) + "orchestration";
            ObjectNode out = om.createObjectNode()
                    .put("correlation_id", after.getTraceId())
                    .put("task_id", String.valueOf(taskId))
                    .put("status", finalStatus);
            taskMessageSender.send(routing, after.getTraceId(), out);
        }
    }

    /** 仅取 exchange 常量（避免与 contract 类名混淆的局部引用） */
    private static final class TaskRunnerExchangeConst {
        static final String TASKS = "stockcalc.tasks";
    }

    private MessageEnvelope parse(String body) {
        try {
            return om.readValue(body, MessageEnvelope.class);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("信封解析失败: " + e.getMessage(), e);
        }
    }
}
