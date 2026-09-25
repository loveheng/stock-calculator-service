package com.zzh.stock_calculator.broker.service;

import com.zzh.stock_calculator.common.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.Map;

/**
 * broker 域编排出口（free-canvas v3 硬约束 #1：main 不直连 :18081/:18082，
 * 一切工具面调用收敛到 :18083 dispatch 单连接）。
 * <p>确定性调用形态：经 Spring AI MCP client（orchestration-dispatch 连接）取 dispatch
 * 工具回调，intentText 直传工具名（DispatchRouter 工具名命中 +100 确定性路由 SYNC_DIRECT），
 * caller=service（程序化调用方，CLARIFY 降级为确定性错误）。连接缺失容错：兜 500 语义。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BrokerDispatchClient {

    private static final String DISPATCH_TOOL = "dispatch";

    private final ObjectProvider<ToolCallbackProvider> toolCallbackProvider;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 经 dispatch 确定性调用 MCP 工具，返回解析后的结果节点（已是工具返回 JSON 本体） */
    public JsonNode invokeTool(String toolName, Map<String, Object> params, String traceId) {
        ToolCallback dispatch = findDispatch();
        ObjectNode input = objectMapper.createObjectNode();
        input.put("intentText", toolName);
        input.put("caller", "service");
        input.put("traceId", traceId);
        input.put("paramsJson", objectMapper.writeValueAsString(params));
        String raw = dispatch.call(input.toString());
        return unwrap(objectMapper.readTree(raw));
    }

    /**
     * dispatch 返回逐层解包：实证形态为 JSON 数组 [{"text":"{\"text\":\"<工具结果JSON>\"}"}]——
     * ToolInvoker 包装数组 → 内层 ToolInvoker {"text":...} → 工具结果本体；也兼容
     * MCP 标准包装 {"content":[{"text":...}]}。文本解不出 JSON 时包装为 error 节点透传上层。
     */
    private JsonNode unwrap(JsonNode node) {
        for (int i = 0; i < 6 && node != null; i++) {
            if (node.isArray()) {
                node = node.isEmpty() ? null : node.get(0);
                continue;
            }
            if (!node.isObject()) {
                break;
            }
            JsonNode contentText = node.path("content").path(0).path("text");
            if (contentText.isTextual()) {
                node = tryParse(contentText.asString());
                continue;
            }
            JsonNode text = node.path("text");
            if (text.isTextual()) {
                node = tryParse(text.asString());
                continue;
            }
            break;
        }
        return node == null ? objectMapper.createObjectNode().put("error", "dispatch 返回空结果") : node;
    }

    private JsonNode tryParse(String text) {
        try {
            return objectMapper.readTree(text);
        } catch (Exception e) {
            return objectMapper.createObjectNode().put("error", text);
        }
    }

    private ToolCallback findDispatch() {
        ToolCallbackProvider provider = toolCallbackProvider.getIfAvailable();
        if (provider == null) {
            throw new BusinessException(500, "编排通道未装配（orchestration-dispatch 连接缺失）");
        }
        for (ToolCallback callback : provider.getToolCallbacks()) {
            if (DISPATCH_TOOL.equals(callback.getToolDefinition().name())) {
                return callback;
            }
        }
        throw new BusinessException(500, "编排通道缺少 dispatch 工具");
    }
}
