package com.zzh.stock_calculator.notify.handler;

import com.zzh.stock_calculator.notify.NotifyCapabilityHandler;
import com.zzh.stockcalc.contract.message.NotifyCapabilityResult;
import com.zzh.stockcalc.contract.message.NotifyCapabilityTask;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * stock_analysis 能力实现（docs/notify/design.md §六：main 能力消费者内部调
 * stock-mcp 经纪人工具）：经 MCP client（spring.ai.mcp.client 自动装配的
 * ToolCallbackProvider）同步调用 stock_analysis 工具，结果原文回流。
 * 步 5 起 MCP 池只挂 :18083 dispatch（工具不再直连注册），本 handler 按名找不到
 * stock_analysis 回调即走「能力未注册」降级文案（不阻启动）；去留随步 7 一并评估。
 * 装配条件：MCP client 已启用（无 MCP 时 notify 的 capability 动作回落
 * 「能力未注册」降级文案，不阻启动）。
 */
@Slf4j
@Component
@ConditionalOnBean(org.springframework.ai.tool.ToolCallbackProvider.class)
public class StockAnalysisCapabilityHandler implements NotifyCapabilityHandler {

    /** 与 stock-mcp StockAnalysisTool 的 @Tool(name) 对齐 */
    static final String TOOL_NAME = "stock_analysis";

    private final ObjectProvider<org.springframework.ai.tool.ToolCallbackProvider>
        mcpToolCallbacksProvider;
    private final ObjectMapper objectMapper;

    public StockAnalysisCapabilityHandler(
        ObjectProvider<org.springframework.ai.tool.ToolCallbackProvider> mcpToolCallbacksProvider,
        ObjectMapper objectMapper
    ) {
        this.mcpToolCallbacksProvider = mcpToolCallbacksProvider;
        this.objectMapper = objectMapper;
    }

    @Override
    public String capability() {
        return TOOL_NAME;
    }

    @Override
    public NotifyCapabilityResult handle(NotifyCapabilityTask task) {
        // params: {stock: 代码或名称, days: 回看根数(可选)}——多余参数丢弃，stock 必带
        Object stock = task.getParams().get("stock");
        if (stock == null || stock.toString().isEmpty()) {
            return NotifyCapabilityResult.builder().reminderId(task.getReminderId())
                .ok(false).summary("缺少 stock 参数").build();
        }
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("stock", stock.toString());
        Object days = task.getParams().get("days");
        if (days != null) {
            args.put("days", days);
        }

        org.springframework.ai.tool.ToolCallbackProvider provider =
            mcpToolCallbacksProvider.getIfAvailable();
        if (provider == null) {
            return NotifyCapabilityResult.builder().reminderId(task.getReminderId())
                .ok(false).summary("MCP 经纪人未启用").build();
        }
        for (ToolCallback callback : provider.getToolCallbacks()) {
            if (TOOL_NAME.equals(callback.getToolDefinition().name())) {
                String json = objectMapper.writeValueAsString(args);
                String result = callback.call(json);
                log.info("[notify-cap] stock_analysis 执行完成 reminderId={} resultChars={}",
                    task.getReminderId(), result == null ? 0 : result.length());
                return NotifyCapabilityResult.builder().reminderId(task.getReminderId())
                    .ok(true).summary(result).build();
            }
        }
        return NotifyCapabilityResult.builder().reminderId(task.getReminderId())
            .ok(false).summary("经纪人未注册工具: " + TOOL_NAME).build();
    }
}
