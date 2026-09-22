package com.zzh.stock_calculator.orchestration;

import com.zzh.stock_calculator.orchestration.config.TraceIdHolder;
import com.zzh.stock_calculator.orchestration.executor.CtxEvaluator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 步 6-0 TraceId 透传单测（纯内存）：ThreadLocal 优先级链 + $ctx env.trace_id 注入。
 */
class TraceIdPassthroughTest {

    @AfterEach
    void cleanup() {
        TraceIdHolder.clear();
    }

    @Test
    void resolve_外部透传优先() {
        String tid = TraceIdHolder.resolve("external-tid");
        assertThat(tid).isEqualTo("external-tid");
    }

    @Test
    void resolve_无外部时取ThreadLocal捕获值() {
        TraceIdHolder.set("captured-from-header");
        assertThat(TraceIdHolder.resolve(null)).isEqualTo("captured-from-header");
    }

    @Test
    void resolve_全空本地生成兜底() {
        assertThat(TraceIdHolder.resolve(null)).isNotBlank();
        assertThat(TraceIdHolder.resolve(" ")).isNotBlank();
    }

    @Test
    void ctx_env注入trace_id可供表达式取用() {
        CtxEvaluator.Ctx ctx = new CtxEvaluator.Ctx(
                new tools.jackson.databind.ObjectMapper().createObjectNode(), "u1", "tid-123");
        var traceId = CtxEvaluator.resolve("$.env.trace_id", ctx);
        assertThat(traceId.asText()).isEqualTo("tid-123");
    }
}
