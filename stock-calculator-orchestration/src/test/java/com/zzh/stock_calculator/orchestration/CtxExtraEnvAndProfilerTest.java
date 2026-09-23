package com.zzh.stock_calculator.orchestration;

import com.zzh.stock_calculator.orchestration.entity.TaskInstanceEntity;
import com.zzh.stock_calculator.orchestration.executor.CtxEvaluator;
import com.zzh.stock_calculator.orchestration.repository.TaskInstanceRepository;
import com.zzh.stock_calculator.orchestration.tool.TaskProfilerTool;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 3②/4② 增强单测（纯内存，不起 Spring）：
 * ①Ctx 扩展 env——extraEnv 合并进 $.env 命名空间（last_execution_time 可寻址），
 *   内置变量不丢；②task_profiler——按 traceId 读 node_states 输出
 *   total_ms + 按耗时降序节点明细，未命中 traceId 返回提示文案。
 */
class CtxExtraEnvAndProfilerTest {

    private final ObjectMapper om = new ObjectMapper();

    // ========== ① Ctx 扩展 env ==========

    @Test
    void extraEnv合并进env命名空间_可寻址() {
        ObjectNode params = om.createObjectNode().put("stock_name", "茅台");
        CtxEvaluator.Ctx ctx = new CtxEvaluator.Ctx(params, "u1", "tid-1",
                Map.of("last_execution_time", "2026-09-22T09:00:00"));
        JsonNode resolved = CtxEvaluator.resolve("$.env.last_execution_time", ctx);
        assertThat(resolved.asText()).isEqualTo("2026-09-22T09:00:00");
        // 内置变量不丢
        assertThat(CtxEvaluator.resolve("$.env.user_id", ctx).asText()).isEqualTo("u1");
        assertThat(CtxEvaluator.resolve("$.env.trace_id", ctx).asText()).isEqualTo("tid-1");
    }

    @Test
    void 默认构造_无扩展env_不报错() {
        CtxEvaluator.Ctx ctx = new CtxEvaluator.Ctx(om.createObjectNode(), "u1", "tid-2");
        assertThat(CtxEvaluator.resolve("$.env.now", ctx).asText()).isNotBlank();
    }

    // ========== ② task_profiler 输出结构 ==========

    private TaskInstanceEntity instanceWithNodeStates() {
        ObjectNode nodeStates = om.createObjectNode();
        ObjectNode slow = nodeStates.putObject("fetch_daily");
        slow.put("status", "done").put("cost_ms", 4200);
        slow.putObject("output").put("head", "...");
        ObjectNode fast = nodeStates.putObject("analyze");
        fast.put("status", "done").put("cost_ms", 800);
        fast.putObject("output").put("head", "...");
        return TaskInstanceEntity.builder()
                .planId(1L).traceId("t-prof").userId("u1")
                .params(om.createObjectNode()).nodeStates(nodeStates)
                .status("done").updatedAt(LocalDateTime.of(2026, 9, 22, 9, 0))
                .build();
    }

    @Test
    void profiler_输出总耗时与降序节点明细() {
        TaskInstanceRepository repo = Mockito.mock(TaskInstanceRepository.class);
        when(repo.findByTraceId("t-prof")).thenReturn(Optional.of(instanceWithNodeStates()));
        TaskProfilerTool tool = new TaskProfilerTool(repo);
        JsonNode report = om.readTree(tool.profileTask("t-prof"));
        assertThat(report.path("traceId").asText()).isEqualTo("t-prof");
        assertThat(report.path("status").asText()).isEqualTo("done");
        assertThat(report.path("total_ms").asLong()).isEqualTo(5000);
        JsonNode nodes = report.path("nodes_by_cost_desc");
        // 降序：最慢节点在前
        assertThat(nodes.propertyNames()).containsExactly("fetch_daily", "analyze");
        assertThat(nodes.path("fetch_daily").path("cost_ms").asLong()).isEqualTo(4200);
    }

    @Test
    void profiler_未知traceId_返回提示文案() {
        TaskInstanceRepository repo = Mockito.mock(TaskInstanceRepository.class);
        when(repo.findByTraceId(anyString())).thenReturn(Optional.empty());
        TaskProfilerTool tool = new TaskProfilerTool(repo);
        assertThat(tool.profileTask("nope")).contains("未找到任务");
    }
}
