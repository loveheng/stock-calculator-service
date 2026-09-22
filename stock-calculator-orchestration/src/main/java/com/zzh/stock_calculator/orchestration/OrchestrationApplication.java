package com.zzh.stock_calculator.orchestration;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Agent 任务编排服务入口（:18083，docs/architecture/agent-orchestration.md D6）。
 * <p>服务拓扑：main :18080（能力+触达）/ mcp :18081（经纪人）/ notify :18082（通知者）/
 * orchestration :18083（编排）——copilot 经 MCP client 递意图，Planner 规划 JSON DAG，
 * Executor 确定性执行。三表（tool_registry/plan/task_instance）复用 stock_mcp 独立库
 * （与 mcp/notify 同库分表，agent 基础设施数据聚合）。</p>
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class OrchestrationApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrchestrationApplication.class, args);
    }
}
