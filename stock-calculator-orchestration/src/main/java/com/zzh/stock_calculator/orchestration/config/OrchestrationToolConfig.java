package com.zzh.stock_calculator.orchestration.config;

import com.zzh.stock_calculator.orchestration.mq.MqObservabilityTool;
import com.zzh.stock_calculator.orchestration.tool.CapabilityTool;
import com.zzh.stock_calculator.orchestration.tool.DispatchTool;
import com.zzh.stock_calculator.orchestration.tool.TaskProfilerTool;
import com.zzh.stock_calculator.orchestration.tool.TaskTool;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MCP 工具注册（mcp 模块 McpToolConfig 同款）：MethodToolCallbackProvider 扫 @Tool 方法
 * 挂到 MCP server。task 工具面（create_task/query_task）是 copilot 的编排入口（§四）；
 * mq 可观测工具面（步 7-2）随 dispatch sync 分流直达；task_profiler（4②）只读剖析耗时；
 * capability（P4③）前置能力清单——copilot 先查清单再进规划。
 */
@Configuration
public class OrchestrationToolConfig {

    @Bean
    public MethodToolCallbackProvider toolCallbackProvider(TaskTool taskTool, DispatchTool dispatchTool,
                                                           MqObservabilityTool mqObservabilityTool,
                                                           TaskProfilerTool taskProfilerTool,
                                                           CapabilityTool capabilityTool) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(taskTool, dispatchTool, mqObservabilityTool, taskProfilerTool, capabilityTool)
                .build();
    }
}
