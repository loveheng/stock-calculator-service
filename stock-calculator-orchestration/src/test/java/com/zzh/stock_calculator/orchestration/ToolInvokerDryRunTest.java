package com.zzh.stock_calculator.orchestration;

import com.zzh.stock_calculator.orchestration.tool.ToolDescriptor;
import com.zzh.stock_calculator.orchestration.tool.ToolInvoker;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ToolInvoker Dry-Run 单测（纯内存）：dryRun=true 时 ①risk=high 工具不真实调用、
 * 返回 mock 标记（dry_run=true + mocked_tool）②risk=read 只读工具照常走真实调用路
 * （无 MCP client → IllegalStateException，证明未走 mock 分支）③dryRun=false 高危
 * 工具不拦截（同样以无 client 报错验证「确实尝试了真实调用」）。不起 Spring。
 */
class ToolInvokerDryRunTest {

    private final ObjectMapper om = new ObjectMapper();
    private final ToolInvoker invoker = new ToolInvoker(
            java.util.List.of(),
            Mockito.mock(org.springframework.web.client.RestClient.Builder.class,
                    Mockito.RETURNS_DEEP_STUBS));

    /** 高危工具用 mcp kind：无可用 MCP client 时真实调用路必然抛 IllegalStateException */
    private ToolDescriptor highDescriptor() {
        return ToolDescriptor.builder()
                .toolName("mock_writer").kind("mcp")
                .endpoint("mcp://x").risk("high")
                .outputPolicy("keep_head").enabled(true)
                .build();
    }

    private ToolDescriptor readDescriptor() {
        return ToolDescriptor.builder()
                .toolName("mock_reader").kind("mcp")
                .endpoint("mcp://x").risk("read")
                .outputPolicy("keep_head").enabled(true)
                .build();
    }

    @Test
    void dryRun高危工具_返回mock不真实调用() {
        JsonNode out = invoker.invoke(highDescriptor(), om.createObjectNode(), "t-1", true);
        assertThat(out.path("dry_run").asBoolean()).isTrue();
        assertThat(out.path("mocked_tool").asText()).isEqualTo("mock_writer");
    }

    @Test
    void dryRun只读工具_照常走真实调用路() {
        assertThatThrownBy(() ->
                invoker.invoke(readDescriptor(), om.createObjectNode(), "t-2", true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("无可用 MCP client");
    }

    @Test
    void 非dryRun高危工具_不拦截() {
        assertThatThrownBy(() ->
                invoker.invoke(highDescriptor(), om.createObjectNode(), "t-3", false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("无可用 MCP client");
    }
}
