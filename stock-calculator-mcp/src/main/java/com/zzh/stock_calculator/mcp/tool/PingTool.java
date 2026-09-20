package com.zzh.stock_calculator.mcp.tool;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * 连通性自检工具：验证 MCP 端点工具注册与调用链路，M2+ 保留作探活。
 */
@Component
public class PingTool {

    @Tool(name = "ping", description = "连通性自检：原样回显输入文本，供 MCP 客户端验证服务可达")
    public String ping(@ToolParam(description = "任意回显文本") String echo) {
        return "pong: " + echo;
    }
}
