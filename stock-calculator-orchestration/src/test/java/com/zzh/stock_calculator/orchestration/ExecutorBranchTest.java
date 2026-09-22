package com.zzh.stock_calculator.orchestration;

import com.zzh.stock_calculator.orchestration.executor.CtxEvaluator;
import com.zzh.stock_calculator.orchestration.executor.Executor;
import com.zzh.stock_calculator.orchestration.mq.TaskMessageSender;
import com.zzh.stock_calculator.orchestration.repository.TaskInstanceRepository;
import com.zzh.stock_calculator.orchestration.tool.ToolDescriptor;
import com.zzh.stock_calculator.orchestration.tool.ToolInvoker;
import com.zzh.stock_calculator.orchestration.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Executor switch/foreach 分支单测（步 7 收口，纯内存）：
 * switch 枚举路由（命中/default/双缺拒绝）+ foreach 展开（$item 替换/空数组/未注册工具）。
 * private 方法经反射调用（executeSwitch/executeForeach 无状态纯逻辑，不起 Spring）。
 */
class ExecutorBranchTest {

    private final ObjectMapper om = new ObjectMapper();
    private final ToolRegistry toolRegistry = Mockito.mock(ToolRegistry.class);
    private final ToolInvoker toolInvoker = Mockito.mock(ToolInvoker.class);
    private final Executor executor = new Executor(
            toolRegistry, toolInvoker,
            Mockito.mock(TaskInstanceRepository.class), Mockito.mock(TaskMessageSender.class));

    private JsonNode invoke(String methodName, Class<?>[] types, Object... args) throws Exception {
        Method m = Executor.class.getDeclaredMethod(methodName, types);
        m.setAccessible(true);
        try {
            return (JsonNode) m.invoke(executor, args);
        } catch (java.lang.reflect.InvocationTargetException e) {
            // 解包反射包装，重抛真实异常（断言直接匹配业务异常类型与 message）
            throw (Exception) e.getCause();
        }
    }

    private CtxEvaluator.Ctx ctxWith(ObjectNode params) {
        CtxEvaluator.Ctx ctx = new CtxEvaluator.Ctx(params, "u1", "tid-1");
        ObjectNode out = om.createObjectNode().put("kind", "hot");
        ctx.putNodeOutput("n1", out);
        return ctx;
    }

    // ===== switch =====

    private ObjectNode switchNode() {
        ObjectNode node = om.createObjectNode()
                .put("id", "s1").put("type", "switch").put("value", "$.nodes.n1.output.kind");
        ObjectNode routes = om.createObjectNode();
        routes.set("hot", om.createObjectNode().put("mode", "fast"));
        routes.set("default", om.createObjectNode().put("mode", "normal"));
        node.set("routes", routes);
        return node;
    }

    @Test
    void switch_命中枚举分支() throws Exception {
        JsonNode out = invoke("executeSwitch", new Class<?>[]{JsonNode.class, CtxEvaluator.Ctx.class},
                switchNode(), ctxWith(om.createObjectNode()));
        assertThat(out.path("branch").asText()).isEqualTo("hot");
        assertThat(out.path("output").path("mode").asText()).isEqualTo("fast");
    }

    @Test
    void switch_未命中走default() throws Exception {
        CtxEvaluator.Ctx ctx = ctxWith(om.createObjectNode());
        ctx.putNodeOutput("n1", om.createObjectNode().put("kind", "cold"));
        JsonNode out = invoke("executeSwitch", new Class<?>[]{JsonNode.class, CtxEvaluator.Ctx.class},
                switchNode(), ctx);
        assertThat(out.path("branch").asText()).isEqualTo("cold");
        assertThat(out.path("output").path("mode").asText()).isEqualTo("normal");
    }

    @Test
    void switch_无命中且无default_拒绝() {
        ObjectNode node = switchNode();
        node.set("routes", om.createObjectNode()); // 清空 routes
        CtxEvaluator.Ctx ctx = ctxWith(om.createObjectNode());
        assertThatThrownBy(() -> invoke("executeSwitch",
                new Class<?>[]{JsonNode.class, CtxEvaluator.Ctx.class}, node, ctx))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("无命中分支");
    }

    // ===== foreach =====

    private ObjectNode foreachNode() {
        ObjectNode node = om.createObjectNode()
                .put("id", "f1").put("type", "foreach").put("over", "$.nodes.n1.output.items")
                .put("tool", "kb_search");
        node.set("input_mapping", om.createObjectNode().put("q", "$.item"));
        return node;
    }

    private CtxEvaluator.Ctx ctxWithItems() {
        CtxEvaluator.Ctx ctx = ctxWith(om.createObjectNode());
        ctx.putNodeOutput("n1", om.createObjectNode()
                .set("items", om.createArrayNode().add("茅台").add("五粮液")));
        return ctx;
    }

    @Test
    void foreach_逐项替换item并收集结果() throws Exception {
        ToolDescriptor descriptor = new ToolDescriptor();
        descriptor.setEnabled(true);
        when(toolRegistry.get("kb_search")).thenReturn(descriptor);
        when(toolInvoker.invoke(any(), any(JsonNode.class), anyString()))
                .thenReturn(om.createObjectNode().put("hit", true));
        JsonNode out = invoke("executeForeach", new Class<?>[]{JsonNode.class, CtxEvaluator.Ctx.class},
                foreachNode(), ctxWithItems());
        assertThat(out.path("items").path("f1:0").path("hit").asBoolean()).isTrue();
        assertThat(out.path("items").path("f1:1").path("hit").asBoolean()).isTrue();
        assertThat(out.path("count").asInt()).isEqualTo(2);
    }

    @Test
    void foreach_空数组_拒绝() {
        CtxEvaluator.Ctx ctx = ctxWith(om.createObjectNode());
        ctx.putNodeOutput("n1", om.createObjectNode().set("items", om.createArrayNode()));
        assertThatThrownBy(() -> invoke("executeForeach",
                new Class<?>[]{JsonNode.class, CtxEvaluator.Ctx.class}, foreachNode(), ctx))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("空数组");
    }

    @Test
    void foreach_子工具未注册_拒绝() {
        when(toolRegistry.get("kb_search")).thenReturn(null);
        assertThatThrownBy(() -> invoke("executeForeach",
                new Class<?>[]{JsonNode.class, CtxEvaluator.Ctx.class}, foreachNode(), ctxWithItems()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("未注册");
    }
}
