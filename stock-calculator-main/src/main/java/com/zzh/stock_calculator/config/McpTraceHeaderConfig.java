package com.zzh.stock_calculator.config;

import com.zzh.stock_calculator.common.TraceContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import io.modelcontextprotocol.client.transport.customizer.McpSyncHttpClientRequestCustomizer;

/**
 * MCP client 透传定制（步 6-0）：给每个发往编排器（:18083）的 MCP 请求注入
 * W3C traceparent header。每次请求生成新 traceparent（每条请求一条链路），
 * 编排器侧经 DispatchTool 的 traceId 参数透传沿用。
 * <p>注入点：mcp-core 的 McpSyncHttpClientRequestCustomizer，spring-ai
 * streamable-http 传输装配自动收集该 Bean（StreamableHttpHttpClientTransportAutoConfiguration）。
 */
@Slf4j
@Configuration
public class McpTraceHeaderConfig {

    @Bean
    public McpSyncHttpClientRequestCustomizer mcpTraceparentCustomizer() {
        return (builder, method, uri, body, context) -> {
            String traceparent = TraceContext.newTraceparent();
            builder.header(TraceContext.TRACEPARENT_HEADER, traceparent);
            if (log.isDebugEnabled()) {
                log.debug("[trace] MCP 请求下发 traceparent={} uri={}", traceparent, uri);
            }
        };
    }
}
