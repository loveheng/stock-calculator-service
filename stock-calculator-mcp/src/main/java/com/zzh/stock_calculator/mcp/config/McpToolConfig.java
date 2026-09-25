package com.zzh.stock_calculator.mcp.config;

import com.zzh.stock_calculator.mcp.tool.ComputeIndicatorsTool;
import com.zzh.stock_calculator.mcp.tool.KbBookListTool;
import com.zzh.stock_calculator.mcp.tool.KbPersonaTool;
import com.zzh.stock_calculator.mcp.tool.KbSearchTool;
import com.zzh.stock_calculator.mcp.tool.FetchKlineTool;
import com.zzh.stock_calculator.mcp.tool.PingTool;
import com.zzh.stock_calculator.mcp.tool.StockAnalysisTool;
import com.zzh.stock_calculator.mcp.tool.StockLevelsTool;
import com.zzh.stock_calculator.mcp.tool.StockDailyTool;
import com.zzh.stock_calculator.mcp.tool.StockRadarCheckTool;
import com.zzh.stock_calculator.mcp.tool.TimeRangeTool;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MCP 工具注册：MethodToolCallbackProvider 扫描 @Tool 方法挂到 MCP server。
 * <p>M3/M4 的工具 bean 就绪后在 provider 的 toolObjects 里追加，避免逐个散配。</p>
 */
@Configuration
public class McpToolConfig {

    @Bean
    public MethodToolCallbackProvider toolCallbackProvider(PingTool pingTool,
                                                           StockAnalysisTool stockAnalysisTool,
                                                           StockDailyTool stockDailyTool,
                                                           StockLevelsTool stockLevelsTool,
                                                           StockRadarCheckTool stockRadarCheckTool,
                                                           FetchKlineTool fetchKlineTool,
                                                           ComputeIndicatorsTool computeIndicatorsTool,
                                                           KbSearchTool kbSearchTool,
                                                           KbBookListTool kbBookListTool,
                                                           KbPersonaTool kbPersonaTool,
                                                           TimeRangeTool timeRangeTool) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(pingTool, stockAnalysisTool, stockDailyTool, stockLevelsTool,
                        stockRadarCheckTool, fetchKlineTool, computeIndicatorsTool, kbSearchTool,
                        kbBookListTool, kbPersonaTool, timeRangeTool)
                .build();
    }
}
