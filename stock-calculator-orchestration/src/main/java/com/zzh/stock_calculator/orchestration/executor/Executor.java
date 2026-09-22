package com.zzh.stock_calculator.orchestration.executor;

import com.zzh.stock_calculator.orchestration.entity.TaskInstanceEntity;
import com.zzh.stock_calculator.orchestration.repository.TaskInstanceRepository;
import com.zzh.stock_calculator.orchestration.tool.ToolDescriptor;
import com.zzh.stock_calculator.orchestration.tool.ToolInvoker;
import com.zzh.stock_calculator.orchestration.tool.ToolRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 确定性 DAG 执行器（agent-orchestration §八/步 4）：纯代码逐节点执行，无 LLM 参与。
 * 节点类型：rest / mcp（本期）+ switch/foreach 控制节点；mq_wait/mq_send 随公告场景接入。
 * <p>硬约束：mq_wait 无 timeout_seconds 直接拒载（§八 Zombie Task 防御）；节点执行前
 * 比对 plan_dag_snapshot 的 paramSchema 与 registry 现值（版本漂移防护，不匹配置 failed）；
 * 每节点落库 node_states（按 output_policy 瘦身），失败从断点重放。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class Executor {

    private final ToolRegistry toolRegistry;
    private final ToolInvoker toolInvoker;
    private final TaskInstanceRepository taskInstanceRepository;

    private final ObjectMapper om = new ObjectMapper();

    /** DAG 拒载校验（硬约束，不靠 prompt 约束 LLM） */
    public void validateDag(JsonNode planDag) {
        for (JsonNode node : planDag.path("nodes")) {
            String type = node.path("type").asText(node.has("tool") ? "tool" : "tool");
            if ("mq_wait".equals(type) && !node.hasNonNull("timeout_seconds")) {
                throw new IllegalArgumentException("mq_wait 节点缺 timeout_seconds（Zombie Task 防御，拒载）: "
                        + node.path("id").asText("?"));
            }
        }
    }

    /**
     * 同步执行入口（rest/mcp/switch/foreach；mq_wait 本期即报不支持——随公告场景接入）。
     * 执行全程落 node_states；任一节点失败即置实例 failed 并落断点（可重放）。
     */
    @Transactional
    public void run(TaskInstanceEntity instance) {
        JsonNode dag = instance.getPlanDagSnapshot();
        try {
            validateDag(dag);
        } catch (IllegalArgumentException e) {
            fail(instance, e.getMessage());
            return;
        }
        CtxEvaluator.Ctx ctx = new CtxEvaluator.Ctx(instance.getParams(), instance.getUserId(), instance.getTraceId());
        ObjectNode nodeStates = om.createObjectNode();

        List<JsonNode> nodes = toList(dag.path("nodes"));
        for (JsonNode node : nodes) {
            String nodeId = node.path("id").asText("n" + nodeStates.size());
            String type = node.path("type").asText(node.has("tool") ? "tool" : "tool");
            long start = System.currentTimeMillis();
            try {
                JsonNode output = executeNode(node, nodeId, type, ctx, instance);
                ctx.putNodeOutput(nodeId, output);
                nodeStates.set(nodeId, nodeState("done", output, node, System.currentTimeMillis() - start));
                log.info("[orchestration] node {} done traceId={} cost={}ms", nodeId, instance.getTraceId(),
                        System.currentTimeMillis() - start);
            } catch (RuntimeException e) {
                nodeStates.set(nodeId, nodeState("failed", om.createObjectNode().put("error", e.getMessage()),
                        node, System.currentTimeMillis() - start));
                instance.setNodeStates(nodeStates);
                fail(instance, "节点 " + nodeId + " 失败: " + e.getMessage());
                return;
            }
        }
        instance.setNodeStates(nodeStates);
        instance.setStatus("done");
        instance.setUpdatedAt(LocalDateTime.now());
        taskInstanceRepository.save(instance);
        log.info("[orchestration] instance {} done traceId={}", instance.getId(), instance.getTraceId());
    }

    private JsonNode executeNode(JsonNode node, String nodeId, String type, CtxEvaluator.Ctx ctx,
                                 TaskInstanceEntity instance) {
        return switch (type) {
            case "switch" -> executeSwitch(node, ctx);
            case "foreach" -> executeForeach(node, ctx);
            case "tool", "rest", "mcp" -> executeTool(node, ctx, instance);
            default -> throw new IllegalArgumentException("暂不支持的节点类型: " + type + "（mq_wait/mq_send 随公告场景接入）");
        };
    }

    /** rest/mcp 工具节点：版本漂移比对 → $ctx 求值 → ToolInvoker */
    private JsonNode executeTool(JsonNode node, CtxEvaluator.Ctx ctx, TaskInstanceEntity instance) {
        String toolName = node.path("tool").asText();
        ToolDescriptor descriptor = toolRegistry.get(toolName);
        if (descriptor == null || !descriptor.isEnabled()) {
            throw new IllegalStateException("工具未注册或已下线: " + toolName);
        }
        // 版本漂移防护（§八）：快照 paramSchema 与 registry 现值比对（以 plan_dag 内嵌 schema 为准）
        if (node.has("param_schema") && !toolRegistry.schemaMatches(toolName, node.path("param_schema"))) {
            throw new IllegalStateException("工具 schema 漂移: " + toolName + "（实例置 failed，不静默崩溃）");
        }
        JsonNode args = CtxEvaluator.evaluate(node.path("input_mapping"), ctx);
        return toolInvoker.invoke(descriptor, args, instance.getTraceId());
    }

    /** switch 枚举路由（§八：取值 → 枚举匹配 → 固定分支，不做表达式求值）。
     *  返回命中分支的 routes.<value>.output 字面量（下游经 $ctx 取用）；未命中走 default。 */
    private JsonNode executeSwitch(JsonNode node, CtxEvaluator.Ctx ctx) {
        String valueExpr = node.path("value").asText();
        JsonNode value = CtxEvaluator.resolve(valueExpr, ctx);
        String key = value.asText();
        JsonNode routes = node.path("routes");
        JsonNode branch = routes.path(key);
        if (branch.isMissingNode()) {
            branch = routes.path("default");
        }
        if (branch.isMissingNode()) {
            throw new IllegalStateException("switch 无命中分支且无 default: " + key);
        }
        ObjectNode out = om.createObjectNode();
        out.put("branch", key);
        out.set("output", branch);
        return out;
    }

    /** foreach 展开（§八）：数组输入逐元素调 tool；本期串行 + 任一失败即中断（on_item_failure=abort
     *  语义；continue 与 min_success_ratio 随并行化接入）。子项 key=<node_id>:<index>。 */
    private JsonNode executeForeach(JsonNode node, CtxEvaluator.Ctx ctx) {
        JsonNode array = CtxEvaluator.resolve(node.path("over").asText(), ctx);
        List<JsonNode> items = CtxEvaluator.toArrayItems(array);
        if (items.isEmpty()) {
            throw new IllegalStateException("foreach 输入为空数组");
        }
        String subTool = node.path("tool").asText();
        ToolDescriptor descriptor = toolRegistry.get(subTool);
        if (descriptor == null) {
            throw new IllegalStateException("foreach 子工具未注册: " + subTool);
        }
        String nodeId = node.path("id").asText("foreach");
        ObjectNode results = om.createObjectNode();
        for (int i = 0; i < items.size(); i++) {
            final int idx = i;
            ObjectNode itemMapping = om.createObjectNode();
            JsonNode mapping = node.path("input_mapping");
            mapping.propertyNames().forEach(name -> {
                JsonNode v = mapping.get(name);
                if (v.isTextual() && "$.item".equals(v.asText())) {
                    itemMapping.set(name, items.get(idx));
                } else {
                    itemMapping.set(name, v);
                }
            });
            ObjectNode subNode = om.createObjectNode();
            subNode.put("tool", subTool);
            subNode.set("input_mapping", itemMapping);
            JsonNode itemOut = executeTool(subNode, ctx,
                    TaskInstanceEntity.builder().traceId(ctx.getEnv().get("trace_id")).params(om.createObjectNode()).build());
            // 子项 key=<node_id>:<index>（§八 foreach 部分失败隔离的落库粒度）
            results.set(nodeId + ":" + i, itemOut);
        }
        ObjectNode out = om.createObjectNode();
        out.set("items", results);
        out.put("count", items.size());
        return out;
    }

    // ========== 辅助 ==========

    private ObjectNode nodeState(String status, JsonNode output, JsonNode node, long costMs) {
        ObjectNode st = om.createObjectNode();
        st.put("status", status);
        st.put("cost_ms", costMs);
        String policy = node.path("output_policy").asText("");
        if (policy.isEmpty()) {
            ToolDescriptor d = toolRegistry.get(node.path("tool").asText());
            policy = d == null ? "keep_head" : d.getOutputPolicy();
        }
        st.set("output", CtxEvaluator.slimForStore(output, policy));
        return st;
    }

    private void fail(TaskInstanceEntity instance, String message) {
        instance.setStatus("failed");
        instance.setUpdatedAt(LocalDateTime.now());
        taskInstanceRepository.save(instance);
        log.warn("[orchestration] instance {} failed traceId={} : {}", instance.getId(), instance.getTraceId(), message);
    }

    private List<JsonNode> toList(JsonNode array) {
        List<JsonNode> out = new ArrayList<>();
        if (array.isArray()) {
            array.forEach(out::add);
        }
        return out;
    }
}
