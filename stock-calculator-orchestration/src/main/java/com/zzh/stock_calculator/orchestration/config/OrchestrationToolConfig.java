package com.zzh.stock_calculator.orchestration.config;

import com.zzh.stock_calculator.orchestration.tool.TaskTool;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MCP 工具注册（mcp 模块 McpToolConfig 同款）：MethodToolCallbackProvider 扫 @Tool 方法
 * 挂到 MCP server。task 工具面（create_task/query_task）是 copilot 的编排入口（§四）。
 */
@Configuration
public class OrchestrationToolConfig {

    @Bean
    public MethodToolCallbackProvider toolCallbackProvider(TaskTool taskTool) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(taskTool)
                .build();
    }
}
