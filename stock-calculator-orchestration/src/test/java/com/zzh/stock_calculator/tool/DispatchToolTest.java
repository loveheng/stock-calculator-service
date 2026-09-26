package com.zzh.stock_calculator.tool;

import com.zzh.stock_calculator.orchestration.entity.ToolRegistryEntity;
import com.zzh.stock_calculator.orchestration.executor.Executor;
import com.zzh.stock_calculator.orchestration.mq.TaskMessageSender;
import com.zzh.stock_calculator.orchestration.planner.Planner;
import com.zzh.stock_calculator.orchestration.repository.TaskInstanceRepository;
import com.zzh.stock_calculator.orchestration.tool.DispatchRouter;
import com.zzh.stock_calculator.orchestration.tool.DispatchTool;
import com.zzh.stock_calculator.orchestration.tool.ToolDescriptor;
import com.zzh.stock_calculator.orchestration.tool.ToolInvoker;
import com.zzh.stock_calculator.orchestration.tool.ToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * dispatch 参数契约预检单测（2026-09-26 画布实证回归锚点）：copilot LLM 只见 dispatch 单工具，
 * 参数名靠猜（symbol/loadToCanvas ≠ fetch_kline 的 stock）——SYNC_DIRECT 前按注册表 schema 预检，
 * 不符回单工具参数契约供同轮自纠，错误不再穿透前端；能力菜单附参数名清单。
 */
@ExtendWith(MockitoExtension.class)
class DispatchToolTest {

    private static final String FETCH_KLINE_SCHEMA =
            "{\"stock\":{\"type\":\"string\",\"required\":true,\"desc\":\"股票代码或名称\"},"
                    + "\"adjustType\":{\"type\":\"string\",\"required\":false,\"desc\":\"qfq|raw\"}}";

    @Mock
    private ToolRegistry toolRegistry;

    @Mock
    private ToolInvoker toolInvoker;

    @Mock
    private DispatchRouter router;

    @Mock
    private Planner planner;

    @Mock
    private Executor executor;

    @Mock
    private TaskInstanceRepository taskInstanceRepository;

    @Mock
    private TaskMessageSender taskMessageSender;

    private final ObjectMapper om = new ObjectMapper();

    private DispatchTool dispatchTool;

    @BeforeEach
    void setUp() {
        dispatchTool = new DispatchTool(toolRegistry, toolInvoker, router, planner,
                executor, taskInstanceRepository, taskMessageSender);
    }

    private ToolDescriptor fetchKlineTool() {
        return ToolDescriptor.builder()
                .toolName("fetch_kline")
                .kind("mcp")
                .description("画布 K 线读穿代理")
                .paramSchema(om.readTree(FETCH_KLINE_SCHEMA))
                .executionMode(ToolRegistry.EM_SYNC)
                .enabled(true)
                .build();
    }

    private void routeSyncDirect(ToolDescriptor tool) {
        lenient().when(router.route(anyString(), anyList()))
                .thenReturn(new DispatchRouter.RouteResult(
                        DispatchRouter.Verdict.SYNC_DIRECT, tool, List.of(tool)));
    }

    @Test
    void syncDirectRejectsInventedParamsAndReturnsContract() {
        ToolDescriptor tool = fetchKlineTool();
        routeSyncDirect(tool);

        String response = dispatchTool.dispatch(
                "fetch_kline 把茅台放上画布", "copilot", null,
                "{\"symbol\":\"sh600519\",\"loadToCanvas\":true}");

        assertThat(response).contains("缺必填参数 stock");
        assertThat(response).contains("未定义参数 symbol");
        assertThat(response).contains("未定义参数 loadToCanvas");
        assertThat(response).contains("stock*(股票代码或名称)");
        assertThat(response).contains("adjustType(qfq|raw)").contains("带*必填");
        verify(toolInvoker, never()).invoke(any(ToolDescriptor.class), any(ObjectNode.class), anyString());
    }

    @Test
    void syncDirectPassesValidArgsThrough() {
        ToolDescriptor tool = fetchKlineTool();
        routeSyncDirect(tool);
        JsonNode result = om.readTree("{\"ok\":true}");
        when(toolInvoker.invoke(eq(tool), any(ObjectNode.class), anyString())).thenReturn(result);

        String response = dispatchTool.dispatch(
                "fetch_kline", "service", null, "{\"stock\":\"sh600519\"}");

        assertThat(response).contains("\"ok\":true");
        verify(toolInvoker).invoke(eq(tool), any(ObjectNode.class), anyString());
    }

    @Test
    void toolWithoutSchemaSkipsValidation() {
        ToolDescriptor tool = ToolDescriptor.builder()
                .toolName("ping").kind("mcp").executionMode(ToolRegistry.EM_SYNC).enabled(true).build();
        routeSyncDirect(tool);
        JsonNode result = om.readTree("{\"pong\":true}");
        when(toolInvoker.invoke(eq(tool), any(ObjectNode.class), anyString())).thenReturn(result);

        String response = dispatchTool.dispatch(
                "ping", "service", null, "{\"anything\":1}");

        assertThat(response).contains("pong");
    }

    @Test
    void clarifyMenuListsParamNames() {
        when(router.route(anyString(), anyList())).thenReturn(new DispatchRouter.RouteResult(
                DispatchRouter.Verdict.CLARIFY, null, List.of()));
        when(toolRegistry.plannable()).thenReturn(List.of(fetchKlineTool()));

        String response = dispatchTool.dispatch("随便说说", null, null, null);

        assertThat(response).contains("fetch_kline");
        assertThat(response).contains("参数：stock*、adjustType（*必填）");
    }
}
