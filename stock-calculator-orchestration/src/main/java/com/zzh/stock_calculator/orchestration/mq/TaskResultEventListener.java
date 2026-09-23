package com.zzh.stock_calculator.orchestration.mq;

import com.zzh.stock_calculator.orchestration.entity.TaskInstanceEntity;
import com.zzh.stock_calculator.orchestration.executor.Executor;
import com.zzh.stock_calculator.orchestration.repository.PlanRepository;
import com.zzh.stock_calculator.orchestration.repository.TaskInstanceRepository;
import com.zzh.stockcalc.contract.MqKey;
import com.zzh.stockcalc.contract.MessageEnvelope;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * mq_wait 结果事件监听器（步 6-2）：消费 task.completed.* / task.failed.* 终态事件，
 * 按 payload.correlation_id（== task_instance.trace_id）找到挂起实例，
 * 标记 mq_wait 节点 done（携带事件输出）后重入 run() 从断点续跑。
 * <p>非挂起实例收到事件忽略（幂等：事件可能先于挂起到达，mq_wait 重入时会再等下一事件；
 * 唤醒丢失由 wait_deadline 超时扫描兜底）。重入 run() 自带 advisory lock 同 plan 串行。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaskResultEventListener {

    private final TaskInstanceRepository taskInstanceRepository;
    private final PlanRepository planRepository;
    private final Executor executor;
    private final ObjectMapper om = new ObjectMapper();

    @RabbitListener(bindings = @org.springframework.amqp.rabbit.annotation.QueueBinding(
            value = @org.springframework.amqp.rabbit.annotation.Queue(
                    value = "orchestration.task.result.q",
                    durable = "true",
                    arguments = @org.springframework.amqp.rabbit.annotation.Argument(
                            name = "x-expires", value = "86400000", type = "java.lang.Long")),
            exchange = @org.springframework.amqp.rabbit.annotation.Exchange(
                    value = MqExchangeConst.TASKS, type = "topic"),
            key = {"task.completed.*", "task.failed.*"}))
    public void onTaskResult(String body) {
        MessageEnvelope envelope = parse(body);
        String routing = envelope.getType();
        boolean failed = routing != null && routing.startsWith(MqKey.TASK_FAILED_PREFIX);
        JsonNode payload = om.valueToTree(envelope.getPayload());
        String correlationId = payload.path("correlation_id").asText("");
        if (correlationId.isBlank()) {
            log.warn("[orchestration] 终态事件缺 correlation_id，忽略: routing={}", routing);
            return;
        }
        taskInstanceRepository.findByTraceId(correlationId).ifPresent(instance -> {
            if (!TaskInstanceEntity.ST_WAITING.equals(instance.getStatus())) {
                log.info("[orchestration] 事件到达但实例非挂起状态（{}），忽略 traceId={}",
                        instance.getStatus(), correlationId);
                return;
            }
            // 唤醒：mq_wait 节点标记 done，输出携带终态与事件 payload，断点续跑
            ObjectNodeHolder states = new ObjectNodeHolder(instance.getNodeStates());
            String waitNodeId = findWaitingNode(instance);
            if (waitNodeId == null) {
                log.warn("[orchestration] 挂起实例找不到 mq_wait 节点，置 failed traceId={}", correlationId);
                instance.setStatus(TaskInstanceEntity.ST_FAILED);
                taskInstanceRepository.save(instance);
                return;
            }
            states.node.set(waitNodeId, om.createObjectNode()
                    .put("status", "done")
                    .put("cost_ms", 0)
                    .set("output", om.createObjectNode()
                            .put("event", routing)
                            .put("failed", failed)
                            .set("payload", payload)));
            instance.setNodeStates(states.node);
            instance.setStatus(TaskInstanceEntity.ST_RUNNING);
            instance.setWaitDeadline(null);
            taskInstanceRepository.save(instance);
            log.info("[orchestration] 唤醒实例 {} node={} event={} traceId={}",
                    instance.getId(), waitNodeId, routing, correlationId);
            executor.run(instance);
            // P1-2 use_count 终态补记（mq_wait 续跑完成的完成点在此而非 TaskRunnerListener）：
            // done 才计数（口径=成功复用）；冒烟实例不计（非业务复用）
            TaskInstanceEntity finished = taskInstanceRepository.findById(instance.getId()).orElse(instance);
            if (TaskInstanceEntity.ST_DONE.equals(finished.getStatus()) && !finished.isSmokeRun()) {
                planRepository.updateUseStats(finished.getPlanId());
            }
        });
    }

    /** 找 node_states 中尚无状态记录的节点 = 挂起时正要执行的 mq_wait（断点定位） */
    private String findWaitingNode(TaskInstanceEntity instance) {
        JsonNode dag = instance.getPlanDagSnapshot();
        for (JsonNode node : dag.path("nodes")) {
            String nid = node.path("id").asText("");
            if ("mq_wait".equals(node.path("type").asText(""))
                    && !instance.getNodeStates().has(nid)) {
                return nid;
            }
        }
        return null;
    }

    /** 包装避免 final 数组 trick（内部类持可变引用） */
    private static final class ObjectNodeHolder {
        final tools.jackson.databind.node.ObjectNode node;
        ObjectNodeHolder(JsonNode src) {
            this.node = src instanceof tools.jackson.databind.node.ObjectNode o
                    ? o : new ObjectMapper().createObjectNode();
        }
    }

    /** 本类只引用 exchange 常量字符串，避免 import 冲突歧义 */
    private static final class MqExchangeConst {
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
