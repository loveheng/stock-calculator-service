package com.zzh.stock_calculator.orchestration;

import com.zzh.stock_calculator.orchestration.executor.CtxEvaluator;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * $ctx 求值器单测（步 7 收口）：三命名空间寻址 / output 语义哨兵 / 数组下标 /
 * 字面量透传 / 越界拒绝 / slimForStore 三策略 / foreach 展开。
 */
class CtxEvaluatorTest {

    private final ObjectMapper om = new ObjectMapper();

    private CtxEvaluator.Ctx ctx() {
        ObjectNode params = om.createObjectNode()
                .put("stock_name", "茅台")
                .put("limit", 5);
        CtxEvaluator.Ctx ctx = new CtxEvaluator.Ctx(params, "u1", "tid-1");
        ObjectNode out = om.createObjectNode()
                .put("title", "公告A")
                .set("items", om.createArrayNode().add("x").add("y"));
        ctx.putNodeOutput("n1", out);
        return ctx;
    }

    // ===== evaluate：input_mapping 逐键求值 =====

    @Test
    void evaluate_路径取值与字面量透传混用() {
        ObjectNode mapping = om.createObjectNode()
                .put("stock", "$.params.stock_name")
                .put("top_n", "$.params.limit")
                .put("mode", "fast")                       // 字面量原样
                .put("last", "$.nodes.n1.output.items.1"); // 数组下标
        ObjectNode result = CtxEvaluator.evaluate(mapping, ctx());
        assertThat(result.path("stock").asText()).isEqualTo("茅台");
        assertThat(result.path("top_n").asInt()).isEqualTo(5);
        assertThat(result.path("mode").asText()).isEqualTo("fast");
        assertThat(result.path("last").asText()).isEqualTo("y");
    }

    @Test
    void evaluate_空或非对象mapping_返回空对象() {
        assertThat(CtxEvaluator.evaluate(null, ctx()).size()).isZero();
        assertThat(CtxEvaluator.evaluate(om.createArrayNode(), ctx()).size()).isZero();
    }

    // ===== resolve：寻址规则与越界拒绝 =====

    @Test
    void resolve_env命名空间可取trace_id() {
        assertThat(CtxEvaluator.resolve("$.env.trace_id", ctx()).asText()).isEqualTo("tid-1");
    }

    @Test
    void resolve_非DollarDot前缀_拒绝() {
        assertThatThrownBy(() -> CtxEvaluator.resolve("params.stock_name", ctx()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("$. 开头");
    }

    @Test
    void resolve_越界命名空间_拒绝() {
        assertThatThrownBy(() -> CtxEvaluator.resolve("$.system.x", ctx()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("越界命名空间");
    }

    @Test
    void resolve_绕过output哨兵_拒绝() {
        assertThatThrownBy(() -> CtxEvaluator.resolve("$.nodes.n1.title", ctx()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("output");
    }

    @Test
    void resolve_上游节点无输出_拒绝() {
        assertThatThrownBy(() -> CtxEvaluator.resolve("$.nodes.ghost.output.title", ctx()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("无输出");
    }

    @Test
    void resolve_字段不存在_拒绝() {
        assertThatThrownBy(() -> CtxEvaluator.resolve("$.params.nope", ctx()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("字段不存在");
    }

    @Test
    void resolve_数组下标越界_拒绝() {
        assertThatThrownBy(() -> CtxEvaluator.resolve("$.nodes.n1.output.items.9", ctx()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("越界");
    }

    // ===== slimForStore：output_policy 三策略 =====

    @Test
    void slim_keep_head截断到2KB() {
        JsonNode out = CtxEvaluator.slimForStore(om.createObjectNode().put("k", "v".repeat(5000)), null);
        assertThat(out.path("head").asText().length()).isLessThanOrEqualTo(2048);
    }

    @Test
    void slim_keep_summary短文本全文保留() {
        JsonNode out = CtxEvaluator.slimForStore(om.createObjectNode().put("s", "短摘要"), "keep_summary");
        assertThat(out.path("summary").asText()).contains("短摘要");
    }

    @Test
    void slim_keep_ref返回引用形态() {
        JsonNode out = CtxEvaluator.slimForStore(om.createObjectNode().put("big", "x"), "keep_ref");
        assertThat(out.path("ref").asText()).isEqualTo("inline-tmp");
    }

    @Test
    void toArrayItems_数组展开与标量兜底() {
        assertThat(CtxEvaluator.toArrayItems(om.createArrayNode().add("a").add("b")))
                .hasSize(2);
        assertThat(CtxEvaluator.toArrayItems(om.createObjectNode())).isEmpty();
    }
}
