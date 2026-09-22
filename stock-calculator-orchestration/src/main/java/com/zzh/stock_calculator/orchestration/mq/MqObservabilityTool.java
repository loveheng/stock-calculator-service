package com.zzh.stock_calculator.orchestration.mq;

import com.zzh.stock_calculator.orchestration.entity.TaskInstanceEntity;
import com.zzh.stock_calculator.orchestration.repository.TaskInstanceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Optional;

/**
 * mq 可观测工具面（步 7-2，agent-orchestration 定案：不设 task 状态类工具防双状态源）：
 * <ul>
 *   <li>queue_stats：队列积压/消费者数（AmqpAdmin 实时计数）；</li>
 *   <li>message_peek：死信/队列消息抽样（get 转 requeue 即窥视，不消费）；</li>
 *   <li>message_trace：按 traceId 查任务实例与节点状态摘要（task_instance 单一事实源）。 </li>
 * </ul>
 * 经 dispatch 网关 sync 分流直调（tool_registry 注册为 sync 工具），copilot 排障口语直达。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MqObservabilityTool {

    /** 常用观测队列（死信 + orchestration 链路三队列），全量队列名可显式传入 */
    private static final List<String> DEFAULT_QUEUES = List.of(
            "dead.q", "orchestration.task.run.q", "orchestration.task.result.q", "async.task.result.q");

    private final AmqpAdmin amqpAdmin;
    private final TaskInstanceRepository taskInstanceRepository;
    private final ObjectMapper om = new ObjectMapper();

    @Tool(name = "queue_stats", description = "查看 MQ 队列实时状态：消息积压数、消费者数。"
            + "不传队列名时返回常用观测队列（死信 + 编排链路队列）概况。排障首选。")
    public String queueStats(
            @ToolParam(description = "队列名（可省，省略时观测常用队列）", required = false) String queueName) {
        List<String> queues = (queueName == null || queueName.isBlank())
                ? DEFAULT_QUEUES : List.of(queueName);
        ObjectNode out = om.createObjectNode();
        for (String q : queues) {
            QueueInformation info = amqpAdmin.getQueueInfo(q);
            ObjectNode n = out.putObject(q);
            if (info == null) {
                n.put("exists", false);
            } else {
                n.put("exists", true)
                        .put("message_count", info.getMessageCount())
                        .put("consumer_count", info.getConsumerCount());
            }
        }
        return out.toString();
    }

    @Tool(name = "message_peek", description = "抽样查看队列头部消息内容（窥视不消费，取后重新入队）。"
            + "用于排查死信原因、验证消息体格式。死信排查先 queue_stats 看 dead.q 积压再 peek。")
    public String messagePeek(
            @ToolParam(description = "队列名，如 dead.q") String queueName) {
        ObjectNode out = om.createObjectNode();
        org.springframework.amqp.core.Message msg = rabbitTemplate.receive(queueName, 500);
        if (msg == null) {
            out.put("empty", true).put("queue", queueName);
            return out.toString();
        }
        // 窥视语义：立即原样放回队尾（不消费；重新入队保持消息可见）
        rabbitTemplate.send(queueName, msg);
        String routing = msg.getMessageProperties().getReceivedRoutingKey();
        out.put("empty", false).put("queue", queueName)
                .put("routing", routing == null ? "" : routing)
                .put("body", new String(msg.getBody()));
        return out.toString();
    }

    @Tool(name = "message_trace", description = "按 traceId 全链路追踪任务：task_instance 的 status、"
            + "wait_deadline、各节点状态与耗时摘要。任务卡住/失败排障用。")
    public String messageTrace(
            @ToolParam(description = "全链路追踪 ID（任务创建时返回的 traceId/correlationId）") String traceId) {
        ObjectNode out = om.createObjectNode();
        Optional<TaskInstanceEntity> found = taskInstanceRepository.findByTraceId(traceId);
        if (found.isEmpty()) {
            return "未找到任务实例 traceId=" + traceId;
        }
        TaskInstanceEntity instance = found.get();
        out.put("task_id", instance.getId())
                .put("plan_id", instance.getPlanId())
                .put("status", instance.getStatus())
                .put("wait_deadline", instance.getWaitDeadline() == null ? "" : instance.getWaitDeadline().toString())
                .set("node_states", instance.getNodeStates());
        return out.toString();
    }

    private final org.springframework.amqp.rabbit.core.RabbitTemplate rabbitTemplate;
}
