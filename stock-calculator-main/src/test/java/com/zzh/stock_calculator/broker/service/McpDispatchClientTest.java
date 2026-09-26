package com.zzh.stock_calculator.broker.service;

import com.zzh.stock_calculator.common.McpDispatchClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * dispatch 客户端解包单测：MCP 标准包装 content[0].text 与 ToolInvoker 兜底 {"text":...}
 * 双层并存时必须解到工具结果本体（klines 恒空的回归锚点）。
 */
@ExtendWith(MockitoExtension.class)
class McpDispatchClientTest {

    private static final String INNER = "{\"stockId\":\"sh600745\",\"klines\":[{\"date\":\"2026-09-24\"}],"
            + "\"coverage\":{\"from\":\"2026-09-24\",\"to\":\"2026-09-24\"}}";

    @Mock
    private ObjectProvider<ToolCallbackProvider> providerProvider;

    @Mock
    private ToolCallbackProvider provider;

    @Mock
    private ToolCallback dispatch;

    @Mock
    private ToolDefinition definition;

    private McpDispatchClient client;

    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void setUp() {
        client = new McpDispatchClient(providerProvider);
        lenient().when(providerProvider.getIfAvailable()).thenReturn(provider);
        lenient().when(provider.getToolCallbacks()).thenReturn(new ToolCallback[]{dispatch});
        lenient().when(dispatch.getToolDefinition()).thenReturn(definition);
        lenient().when(definition.name()).thenReturn("dispatch");
    }

    @Test
    void unwrapsMcpContentThenInvokerText() {
        // 双层包装：MCP content[0].text 内嵌 ToolInvoker {"text":"<json>"}（2026-09-24 实证形态）
        String mcpWrapped = "{\"content\":[{\"type\":\"text\",\"text\":"
                + json.writeValueAsString("{\"text\":" + json.writeValueAsString(INNER) + "}")
                + "}],\"isError\":false}";
        when(dispatch.call(anyString())).thenReturn(mcpWrapped);

        JsonNode node = client.invokeTool("fetch_kline", Map.of(), "trace-1");
        assertEquals("sh600745", node.path("stockId").asString());
        assertEquals(1, node.path("klines").size());
    }

    @Test
    void unwrapsArrayOfTextLayers() {
        // 实测主形态：ToolInvoker 包装数组 → 内层 {"text":...} → 工具结果本体
        String arrayWrapped = "[" + json.writeValueAsString(
                Map.of("text", "{\"text\":" + json.writeValueAsString(INNER) + "}")) + "]";
        when(dispatch.call(anyString())).thenReturn(arrayWrapped);

        JsonNode node = client.invokeTool("fetch_kline", Map.of(), "trace-1");
        assertEquals("sh600745", node.path("stockId").asString());
        assertEquals(1, node.path("klines").size());
    }

    @Test
    void passthroughPlainJsonResult() {
        when(dispatch.call(anyString())).thenReturn(INNER);

        JsonNode node = client.invokeTool("fetch_kline", Map.of(), "trace-1");
        assertEquals(1, node.path("klines").size());
    }

    @Test
    void nonJsonTextBecomesErrorNode() {
        String mcpWrapped = "{\"content\":[{\"type\":\"text\",\"text\":\"工具不存在\"}],\"isError\":true}";
        when(dispatch.call(anyString())).thenReturn(mcpWrapped);

        JsonNode node = client.invokeTool("nope", Map.of(), "trace-1");
        assertTrue(node.has("error"));
    }

    @Test
    void keepsToolResultWhenInnerTextIsPlainString() {
        // ocr 工具实证形态：本体 {"text":"<纯识别文本>","length":n}——内层 text 不是 JSON，
        // 必须停在结果本体，不能继续下探并把识别文本误包装成 error（2026-09-26 OCR 误判 503 回归锚点）
        String ocrText = "成交时间:2026-08-24 10:45:50\n证券代码:600745";
        String wrapped = "[" + json.writeValueAsString(
                Map.of("text", "{\"text\":" + json.writeValueAsString(ocrText) + ",\"length\":" + ocrText.length() + "}"))
                + "]";
        when(dispatch.call(anyString())).thenReturn(wrapped);

        JsonNode node = client.invokeTool("ocr", Map.of(), "trace-1");
        assertEquals(ocrText, node.path("text").asString());
        assertTrue(node.path("error").isMissingNode());
    }
}
