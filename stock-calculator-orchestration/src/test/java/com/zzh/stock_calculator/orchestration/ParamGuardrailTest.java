package com.zzh.stock_calculator.orchestration;

import com.zzh.stock_calculator.orchestration.planner.ParamGuardrail;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ParamGuardrail 参数兜底单测（纯内存）：①6 位裸代码按前缀补市场后缀
 * （6 开头→.SH，其余→.SZ）②「N 年」超 20 年裁剪 ③已带后缀/合规值原样透传
 * ④非 stock/range 槽位不动。不依赖 Spring。
 */
class ParamGuardrailTest {

    private final ObjectMapper om = new ObjectMapper();

    private JsonNode slots(String name) {
        ObjectNode root = om.createObjectNode();
        root.putArray("slots").add(om.createObjectNode().put("name", name).put("type", "string"));
        // correct() 期望的是 slots 数组节点本身
        return root.get("slots");
    }

    @Test
    void 六位裸代码_6开头补SH() {
        ObjectNode filled = om.createObjectNode().put("stock_code", "600519");
        ParamGuardrail.correct(filled, slots("stock_code"));
        assertThat(filled.path("stock_code").asText()).isEqualTo("600519.SH");
    }

    @Test
    void 六位裸代码_非6开头补SZ() {
        ObjectNode filled = om.createObjectNode().put("stock_id", "300072");
        ParamGuardrail.correct(filled, slots("stock_id"));
        assertThat(filled.path("stock_id").asText()).isEqualTo("300072.SZ");
    }

    @Test
    void 已带后缀_不重复补() {
        ObjectNode filled = om.createObjectNode().put("stock_code", "600519.SH");
        ParamGuardrail.correct(filled, slots("stock_code"));
        assertThat(filled.path("stock_code").asText()).isEqualTo("600519.SH");
    }

    @Test
    void 超长年限_裁剪到20() {
        ObjectNode filled = om.createObjectNode().put("time_range", "过去100年日线");
        ParamGuardrail.correct(filled, slots("time_range"));
        assertThat(filled.path("time_range").asText()).isEqualTo("过去20年日线");
    }

    @Test
    void 合规年限_原样透传() {
        ObjectNode filled = om.createObjectNode().put("time_range", "近5年");
        ParamGuardrail.correct(filled, slots("time_range"));
        assertThat(filled.path("time_range").asText()).isEqualTo("近5年");
    }

    @Test
    void 非目标槽位_不动() {
        ObjectNode filled = om.createObjectNode().put("keywords", "600519");
        ParamGuardrail.correct(filled, slots("keywords"));
        assertThat(filled.path("keywords").asText()).isEqualTo("600519");
    }
}
