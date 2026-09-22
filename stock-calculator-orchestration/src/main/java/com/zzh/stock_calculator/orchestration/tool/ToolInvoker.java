package com.zzh.stock_calculator.orchestration.tool;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 * 统一工具调用入口（agent-orchestration D5/步 2）：按 descriptor.kind 分派——
 * mcp → :18081 经纪人 callTool（starter 自动装配的 McpSyncClient）；
 * rest → main 接口 HTTP 调用（endpoint 形如 "GET /api/..."，相对 main :18080）。
 * traceId 经 X-Trace-Id 头贯穿（D9）；调用即打点（节点级耗时随日志沉淀）。
 */
@Slf4j
@Component
public class ToolInvoker {

    public static final String TRACE_HEADER = "X-Trace-Id";
    private static final String MAIN_BASE = "http://localhost:18080";

    private final List<McpSyncClient> mcpSyncClients;
    private final RestClient restClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ToolInvoker(List<McpSyncClient> mcpSyncClients, RestClient.Builder restClientBuilder) {
        this.mcpSyncClients = mcpSyncClients;
        this.restClient = restClientBuilder.build();
    }

    /**
     * 执行单次工具调用。
     *
     * @param descriptor registry 描述符（决定分派路）
     * @param arguments  已由 Executor 按 $ctx 求值后的参数对象
     * @param traceId    全链路追踪键（D9）
     * @return 结构化输出（mcp 取 structuredContent/content 文本；rest 取响应 JSON）
     */
    public JsonNode invoke(ToolDescriptor descriptor, JsonNode arguments, String traceId) {
        long start = System.currentTimeMillis();
        JsonNode out = ToolRegistry.KIND_MCP.equals(descriptor.getKind())
                ? invokeMcp(descriptor, arguments, traceId)
                : invokeRest(descriptor, arguments, traceId);
        log.info("[orchestration] tool invoke {} kind={} traceId={} cost={}ms",
                descriptor.getToolName(), descriptor.getKind(), traceId, System.currentTimeMillis() - start);
        return out;
    }

    private JsonNode invokeMcp(ToolDescriptor descriptor, JsonNode arguments, String traceId) {
        McpSyncClient client = mcpSyncClients.stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("无可用 MCP client 连接（:18081）"));
        McpSchema.CallToolResult result = client.callTool(new McpSchema.CallToolRequest(
                descriptor.getToolName(), objectMapper.convertValue(arguments, Map.class)));
        if (Boolean.TRUE.equals(result.isError())) {
            throw new IllegalStateException("mcp 工具报错: " + descriptor.getToolName()
                    + " traceId=" + traceId + " content=" + result.content());
        }
        if (result.structuredContent() != null) {
            return objectMapper.valueToTree(result.structuredContent());
        }
        // 无结构化输出的工具（kb_search 等）：取首个 text content 包成 {"text": ...}
        StringBuilder text = new StringBuilder();
        for (McpSchema.Content c : result.content()) {
            if (c instanceof McpSchema.TextContent tc) {
                text.append(tc.text());
            }
        }
        return objectMapper.createObjectNode().put("text", text.toString());
    }

    private JsonNode invokeRest(ToolDescriptor descriptor, JsonNode arguments, String traceId) {
        String method = descriptor.getEndpoint().contains(" ")
                ? descriptor.getEndpoint().substring(0, descriptor.getEndpoint().indexOf(' ')).trim()
                : "GET";
        String path = descriptor.getEndpoint().contains(" ")
                ? descriptor.getEndpoint().substring(descriptor.getEndpoint().indexOf(' ') + 1).trim()
                : descriptor.getEndpoint();
        String url = MAIN_BASE + path;
        // uri() 必须紧随 method()（RequestBodySpec 继承 RequestHeadersSpec，无 uri 重载），
        // header 在 uri 之后追加
        RestClient.RequestBodySpec spec = restClient
                .method(HttpMethod.valueOf(method.toUpperCase()))
                .uri(URI.create(url))
                .header(TRACE_HEADER, traceId);
        ResponseEntity<String> resp;
        if ("GET".equalsIgnoreCase(method)) {
            // GET：参数拼 query（arguments 平铺）
            StringBuilder qs = new StringBuilder();
            for (String name : arguments.propertyNames()) {
                qs.append(qs.isEmpty() ? '?' : '&').append(name).append('=')
                        .append(arguments.get(name).asText());
            }
            resp = restClient.method(HttpMethod.GET)
                    .uri(URI.create(url + qs))
                    .header(TRACE_HEADER, traceId)
                    .retrieve().toEntity(String.class);
        } else {
            resp = spec.body(arguments).retrieve().toEntity(String.class);
        }
        String body = resp.getBody();
        if (body == null || body.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(body);
        } catch (RuntimeException e) {
            return objectMapper.createObjectNode().put("text", body);
        }
    }
}
