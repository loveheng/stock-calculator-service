package com.zzh.stock_calculator.orchestration;

import com.zzh.stock_calculator.orchestration.tool.McpBrokerClientProvider;
import com.zzh.stock_calculator.orchestration.tool.ToolDescriptor;
import com.zzh.stock_calculator.orchestration.tool.ToolInvoker;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;

/**
 * ToolInvoker 会话语义守卫（2026-09-27 启动解耦档 2）：ToolInvoker 不再持有握手状态，
 * 会话生命周期完全交给 McpBrokerClientProvider，本类锁住它与 provider 的三条契约：
 * ① 每次工具调用都走 provider 取值（由其缓存复用，不再 spawn 重复握手）；
 * ② callTool 会话级异常 → 触发 discard（下次调用全新建连，对端重启后可自愈）；
 * ③ 工具业务报错（isError=true）不触发 discard（会话仍然健康）。
 * 纯 Mockito，不起 Spring。
 */
class ToolInvokerBrokerSessionTest {

    private final ObjectMapper om = new ObjectMapper();

    private final McpSyncClient client = Mockito.mock(McpSyncClient.class);

    private final McpBrokerClientProvider provider = Mockito.mock(McpBrokerClientProvider.class);

    private final ToolInvoker invoker = new ToolInvoker(this.provider,
            Mockito.mock(RestClient.Builder.class, Mockito.RETURNS_DEEP_STUBS));

    private ToolDescriptor descriptor() {
        return ToolDescriptor.builder()
                .toolName("mock_reader").kind("mcp")
                .endpoint("mcp://x").risk("read")
                .outputPolicy("keep_head").enabled(true)
                .build();
    }

    private void givenSession() {
        Mockito.doReturn(this.client).when(this.provider).obtain();
    }

    private void givenSuccessResult() {
        McpSchema.CallToolResult result = Mockito.mock(McpSchema.CallToolResult.class);
        Mockito.when(result.isError()).thenReturn(false);
        Mockito.when(result.structuredContent()).thenReturn(null);
        Mockito.when(result.content()).thenReturn(List.of());
        Mockito.doReturn(result).when(this.client).callTool(any());
    }

    @Test
    @DisplayName("每次调用都经 provider 取值，握手/复用由 provider 负责")
    void goesThroughProviderEveryInvoke() {
        givenSession();
        givenSuccessResult();

        this.invoker.invoke(descriptor(), this.om.createObjectNode(), "t-1", false);
        this.invoker.invoke(descriptor(), this.om.createObjectNode(), "t-2", false);

        Mockito.verify(this.provider, Mockito.times(2)).obtain();
        Mockito.verify(this.client, Mockito.times(2)).callTool(any());
        // ToolInvoker 侧已无握手职责：不允许它自己碰 initialize
        Mockito.verify(this.client, Mockito.never()).initialize();
    }

    @Test
    @DisplayName("会话级异常 → discard（下次可全新建连自愈）")
    void sessionFailureTriggersDiscard() {
        givenSession();
        givenSuccessResult();
        this.invoker.invoke(descriptor(), this.om.createObjectNode(), "t-1", false);

        Mockito.doThrow(new IllegalStateException("connection reset by broker"))
                .when(this.client).callTool(any());
        assertThatThrownBy(() -> this.invoker.invoke(descriptor(), this.om.createObjectNode(), "t-2", false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("connection reset by broker");

        Mockito.verify(this.provider, Mockito.times(1)).discard();
    }

    @Test
    @DisplayName("工具业务报错不 discard（会话仍健康）")
    void toolErrorKeepsSession() {
        givenSession();
        givenSuccessResult();
        this.invoker.invoke(descriptor(), this.om.createObjectNode(), "t-1", false);

        McpSchema.CallToolResult failed = Mockito.mock(McpSchema.CallToolResult.class);
        Mockito.when(failed.isError()).thenReturn(true);
        Mockito.when(failed.content()).thenReturn(List.of());
        Mockito.doReturn(failed).when(this.client).callTool(any());
        assertThatThrownBy(() -> this.invoker.invoke(descriptor(), this.om.createObjectNode(), "t-2", false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("mcp 工具报错");

        givenSuccessResult();
        assertThat(this.invoker.invoke(descriptor(), this.om.createObjectNode(), "t-3", false)).isNotNull();
        Mockito.verify(this.provider, Mockito.never()).discard();
    }
}
