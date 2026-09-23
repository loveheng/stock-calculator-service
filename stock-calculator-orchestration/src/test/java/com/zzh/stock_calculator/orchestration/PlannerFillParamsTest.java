package com.zzh.stock_calculator.orchestration;

import com.zzh.stock_calculator.orchestration.entity.PlanEntity;
import com.zzh.stock_calculator.orchestration.planner.Planner;
import com.zzh.stock_calculator.orchestration.planner.PlannerLlmClient;
import com.zzh.stock_calculator.orchestration.repository.PlanRepository;
import com.zzh.stock_calculator.orchestration.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Planner 填槽校验单测（步 7 收口，纯内存）：fillParams 私有方法经反射驱动——
 * ①LLM 报 __missing → null（回退重规划）②required 槽位缺 → null
 * ③全槽位齐 → 原样返回 ④LLM 异常 → null 兜底。
 * 依赖全 mock，不起 Spring。
 */
class PlannerFillParamsTest {

    private final ObjectMapper om = new ObjectMapper();
    private final PlannerLlmClient llmClient = Mockito.mock(PlannerLlmClient.class);
    private final Planner planner = new Planner(
            llmClient,
            Mockito.mock(com.zzh.stock_calculator.orchestration.planner.IntentEmbeddingClient.class),
            Mockito.mock(PlanRepository.class),
            Mockito.mock(com.zzh.stock_calculator.orchestration.repository.MatchLogRepository.class),
            Mockito.mock(com.zzh.stock_calculator.orchestration.hitl.SmokeGateService.class),
            Mockito.mock(ToolRegistry.class));

    /** 槽位定义：stock_name 必填 + limit 可选 */
    private PlanEntity plan() {
        ObjectNode slots = om.createObjectNode();
        var arr = slots.putArray("slots");
        arr.add(om.createObjectNode().put("name", "stock_name").put("type", "string").put("required", true));
        arr.add(om.createObjectNode().put("name", "limit").put("type", "int").put("required", false));
        return PlanEntity.builder().intentText("订阅公告").paramSchema(slots).build();
    }

    private JsonNode fillParams(String llmResp) throws Exception {
        when(llmClient.chat(anyString(), anyString(), anyBoolean())).thenReturn(llmResp);
        Method m = Planner.class.getDeclaredMethod("fillParams", PlanEntity.class, String.class);
        m.setAccessible(true);
        return (JsonNode) m.invoke(planner, plan(), "订阅茅台公告");
    }

    @Test
    void llm报missing_返回null回退重规划() throws Exception {
        assertThat(fillParams("{\"__missing\":[\"stock_name\"]}")).isNull();
    }

    @Test
    void required槽位缺失_返回null() throws Exception {
        // 只填可选槽位，缺 required 的 stock_name
        assertThat(fillParams("{\"limit\":5}")).isNull();
    }

    @Test
    void 全槽位齐_原样返回填充值() throws Exception {
        JsonNode out = fillParams("{\"stock_name\":\"茅台\",\"limit\":5}");
        assertThat(out).isNotNull();
        assertThat(out.path("stock_name").asText()).isEqualTo("茅台");
        assertThat(out.path("limit").asInt()).isEqualTo(5);
    }

    @Test
    void llm异常_返回null兜底() throws Exception {
        when(llmClient.chat(anyString(), anyString(), anyBoolean()))
                .thenThrow(new RuntimeException("llm 超时"));
        Method m = Planner.class.getDeclaredMethod("fillParams", PlanEntity.class, String.class);
        m.setAccessible(true);
        assertThat((JsonNode) m.invoke(planner, plan(), "x")).isNull();
    }
}
