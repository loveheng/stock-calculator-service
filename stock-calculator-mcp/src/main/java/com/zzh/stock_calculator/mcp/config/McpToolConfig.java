package com.zzh.stock_calculator.mcp.config;

import com.zzh.stock_calculator.mcp.tool.ComputeIndicatorsTool;
import com.zzh.stock_calculator.mcp.tool.FetchRealtimeQuoteTool;
import com.zzh.stock_calculator.mcp.tool.KbBookListTool;
import com.zzh.stock_calculator.mcp.tool.KbPersonaTool;
import com.zzh.stock_calculator.mcp.tool.KbSearchTool;
import com.zzh.stock_calculator.mcp.tool.FetchKlineTool;
import com.zzh.stock_calculator.mcp.tool.PingTool;
import com.zzh.stock_calculator.mcp.tool.StockAnalysisTool;
import com.zzh.stock_calculator.mcp.tool.StockLevelsTool;
import com.zzh.stock_calculator.mcp.tool.StockDailyTool;
import com.zzh.stock_calculator.mcp.tool.StockRadarBatchTool;
import com.zzh.stock_calculator.mcp.tool.StockRadarCheckTool;
import com.zzh.stock_calculator.mcp.tool.TimeRangeTool;
import com.zzh.stock_calculator.mcp.vision.OcrTool;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
/**
 * MCP 工具注册：MethodToolCallbackProvider 扫描 @Tool 方法挂到 MCP server。
 * <p>M3/M4 的工具 bean 就绪后在 provider 的 toolObjects 里追加，避免逐个散配。</p>
 */
@Configuration
@EnableConfigurationProperties(com.zzh.stock_calculator.mcp.vision.OcrProperties.class)
public class McpToolConfig {

    @Bean
    public MethodToolCallbackProvider toolCallbackProvider(PingTool pingTool,
                                                           StockAnalysisTool stockAnalysisTool,
                                                           StockDailyTool stockDailyTool,
                                                           StockLevelsTool stockLevelsTool,
                                                           StockRadarCheckTool stockRadarCheckTool,
                                                           StockRadarBatchTool stockRadarBatchTool,
                                                           FetchRealtimeQuoteTool fetchRealtimeQuoteTool,
                                                           FetchKlineTool fetchKlineTool,
                                                           ComputeIndicatorsTool computeIndicatorsTool,
                                                           KbSearchTool kbSearchTool,
                                                           KbBookListTool kbBookListTool,
                                                           KbPersonaTool kbPersonaTool,
                                                           TimeRangeTool timeRangeTool,
                                                           com.zzh.stock_calculator.mcp.vision.OcrTool ocrTool) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(pingTool, stockAnalysisTool, stockDailyTool, stockLevelsTool,
                        stockRadarCheckTool, stockRadarBatchTool, fetchRealtimeQuoteTool, fetchKlineTool,
                        computeIndicatorsTool,
                        kbSearchTool, kbBookListTool, kbPersonaTool, timeRangeTool, ocrTool)
                .build();
    }
}
