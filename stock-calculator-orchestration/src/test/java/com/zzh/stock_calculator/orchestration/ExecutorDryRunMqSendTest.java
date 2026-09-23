package com.zzh.stock_calculator.orchestration;

import com.zzh.stock_calculator.orchestration.entity.TaskInstanceEntity;
import com.zzh.stock_calculator.orchestration.executor.Executor;
import com.zzh.stock_calculator.orchestration.mq.TaskMessageSender;
import com.zzh.stock_calculator.orchestration.repository.TaskInstanceRepository;
import com.zzh.stock_calculator.orchestration.tool.ToolInvoker;
import com.zzh.stock_calculator.orchestration.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Executor Dry-Run mq_send 拦截单测（纯内存，依赖全 mock 不起 Spring）：
 * params.dry_run=true 时 ①mq_send 节点不真实下发（TaskMessageSender 零调用），
 * 节点输出带 dry_run=true + mocked_routing_key，实例正常收尾 done；
 * ②dry_run=false 时真实下发（send 被调用）。工具节点侧拦截已在 ToolInvokerDryRunTest 覆盖。
 */
class ExecutorDryRunMqSendTest {

    private final ObjectMapper om = new ObjectMapper();
    private final ToolRegistry toolRegistry = Mockito.mock(ToolRegistry.class);
    private final ToolInvoker toolInvoker = Mockito.mock(ToolInvoker.class);
    private final TaskInstanceRepository repo = Mockito.mock(TaskInstanceRepository.class);
    private final TaskMessageSender sender = Mockito.mock(TaskMessageSender.class);
    private final Executor executor = new Executor(toolRegistry, toolInvoker, repo, sender,
            Mockito.mock(com.zzh.stock_calculator.orchestration.mq.MqWaitWakeService.class));

    private TaskInstanceEntity instance(boolean dryRun) {
        ObjectNode params = om.createObjectNode();
        params.put("dry_run", dryRun);
        JsonNode dag = om.readTree("""
                {"nodes":[{"id":"n1","type":"mq_send","routing_key":"task.notify.mock",
                "payload_mapping":{"text":"hi"}}]}
                """);
        return TaskInstanceEntity.builder()
                .planId(1L).planDagSnapshot(dag).traceId("t-dry")
                .userId("u1").params(params).status("running").build();
    }

    @Test
    void dryRun时mq_send不下发_输出mock标记_实例done() {
        when(repo.findTopByPlanIdAndStatusOrderByUpdatedAtDesc(any(), any()))
                .thenReturn(Optional.empty());
        TaskInstanceEntity inst = instance(true);
        executor.run(inst);
        verify(sender, Mockito.never()).send(anyString(), anyString(), any());
        assertThat(inst.getStatus()).isEqualTo("done");
        // node_states 落库按 keep_head 瘦身成 {"head": "..."}，mock 标记序列化在 head 字符串里
        String head = inst.getNodeStates().path("n1").path("output").path("head").asText();
        assertThat(head).contains("\"dry_run\":true").contains("task.notify.mock");
    }

    @Test
    void 非dryRun时mq_send真实下发() {
        when(repo.findTopByPlanIdAndStatusOrderByUpdatedAtDesc(any(), any()))
                .thenReturn(Optional.empty());
        when(sender.send(anyString(), anyString(), any())).thenReturn("mid-1");
        TaskInstanceEntity inst = instance(false);
        executor.run(inst);
        verify(sender).send(Mockito.eq("task.notify.mock"), Mockito.eq("t-dry"), any());
        assertThat(inst.getStatus()).isEqualTo("done");
        // keep_head 瘦身：message_id 序列化在 head 字符串里
        assertThat(inst.getNodeStates().path("n1").path("output").path("head").asText())
                .contains("mid-1");
    }
}
