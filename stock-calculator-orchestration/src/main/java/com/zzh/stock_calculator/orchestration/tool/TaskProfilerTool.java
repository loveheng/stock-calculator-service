package com.zzh.stock_calculator.orchestration.tool;

import com.zzh.stock_calculator.orchestration.entity.TaskInstanceEntity;
import com.zzh.stock_calculator.orchestration.repository.TaskInstanceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * task_profiler MCP 工具面（agent-orchestration §八 节点流转白盒化 / 增强 4②）：
 * 读 task_instance.node_states 的节点级耗时（cost_ms，Executor 落库），
 * 输出结构化剖析视图供 copilot 用自然语言回答「卡在哪了」。只读，不触发执行。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaskProfilerTool {

    private final TaskInstanceRepository taskInstanceRepository;
    private final ObjectMapper om = new ObjectMapper();

    @Tool(name = "task_profiler", description = "剖析编排任务耗时：按 traceId 读各节点执行耗时（cost_ms），"
            + "返回总耗时、最慢节点排名与各节点明细。用于回答「这个任务跑了多久/卡在哪了」。")
    public String profileTask(
            @ToolParam(description = "创建任务时返回的 traceId") String traceId) {
        Optional<TaskInstanceEntity> found = taskInstanceRepository.findByTraceId(traceId);
        if (found.isEmpty()) {
            return "未找到任务：traceId=" + traceId;
        }
        TaskInstanceEntity instance = found.get();
        JsonNode nodeStates = instance.getNodeStates();
        ObjectNode report = om.createObjectNode();
        report.put("traceId", traceId);
        report.put("status", instance.getStatus());

        long totalMs = 0;
        ObjectNode nodes = om.createObjectNode();
        // 最慢节点 top：node_states 为无序对象，按 cost_ms 降序排（并列按节点名稳定序）
        List<String> ids = new java.util.ArrayList<>(nodeStates.propertyNames());
        ids.sort(Comparator.comparingLong((String id) -> nodeStates.path(id).path("cost_ms").asLong(0)).reversed());
        for (String nodeId : ids) {
            JsonNode st = nodeStates.get(nodeId);
            long cost = st.path("cost_ms").asLong(0);
            totalMs += cost;
            ObjectNode n = om.createObjectNode();
            n.put("status", st.path("status").asText());
            n.put("cost_ms", cost);
            n.set("output_head", st.path("output"));
            nodes.set(nodeId, n);
        }
        report.put("total_ms", totalMs);
        report.set("nodes_by_cost_desc", nodes);
        return report.toString();
    }
}
