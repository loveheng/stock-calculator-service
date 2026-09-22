package com.zzh.stock_calculator.orchestration.planner;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Planner 自带 LLM 客户端（agent-orchestration D6 代价：不复用 main llm 域 fallback 链）。
 * OpenAI 兼容 /chat/completions 原生 REST，连接三键复用 .env 的 DEEPSEEK_*（mcp KbLlmClient
 * 同款套路：显式 HTTP/1.1 工厂 + byte[] 收包 UTF-8 自解码 + finish_reason=length 显式检测）。
 */
@Slf4j
@Component
public class PlannerLlmClient {

    private static final int MAX_ATTEMPTS = 3;

    private final RestClient restClient;
    private final String baseUrl;
    private final String model;
    private final String apiToken;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public PlannerLlmClient(RestClient.Builder builder,
                            @Value("${orchestration.llm.base-url:}") String baseUrl,
                            @Value("${orchestration.llm.api-key:}") String apiKey,
                            @Value("${orchestration.llm.model:deepseek-chat}") String model) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10_000);
        factory.setReadTimeout(120_000);
        this.restClient = builder.requestFactory(factory).build();
        this.model = model;
        this.apiToken = apiKey;
        if (baseUrl == null || baseUrl.isBlank() || apiKey == null || apiKey.isBlank()) {
            log.warn("DEEPSEEK_BASE_URL / DEEPSEEK_API_KEY 未配置：Planner 规划不可用（create_task 将报错）");
        }
        this.baseUrl = baseUrl == null ? "" : baseUrl.replaceAll("/+$", "");
    }

    /**
     * 单轮对话（system + user）。jsonMode=true 时 response_format=json_object（规划产物为
     * 严格 JSON schema，§七）；yes/no 廉价校验与意图规范化也走 JSON（{"ok":...} 统一解析路）。
     */
    public String chat(String systemPrompt, String userMessage, boolean jsonMode) {
        if (baseUrl.isBlank() || apiToken == null || apiToken.isBlank()) {
            throw new IllegalStateException("Planner LLM 未配置（DEEPSEEK_BASE_URL / DEEPSEEK_API_KEY）");
        }
        String rf = jsonMode ? ",\"response_format\":{\"type\":\"json_object\"}" : "";
        String body = "{\"model\":\"" + model + "\",\"stream\":false,\"max_tokens\":4096" + rf
                + ",\"messages\":["
                + "{\"role\":\"system\",\"content\":" + objectMapper.writeValueAsString(systemPrompt) + "},"
                + "{\"role\":\"user\",\"content\":" + objectMapper.writeValueAsString(userMessage) + "}]}";
        RuntimeException last = null;
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            try {
                byte[] resp = restClient.post()
                        .uri(baseUrl + "/chat/completions")
                        .header("Authorization", "Bearer " + apiToken)
                        .header("Content-Type", "application/json")
                        .body(body)
                        .retrieve()
                        .body(byte[].class);
                return parseContent(new String(resp, java.nio.charset.StandardCharsets.UTF_8));
            } catch (RuntimeException e) {
                last = e;
                log.warn("[orchestration] Planner LLM 调用失败（第 {} 次）: {}", attempt + 1, e.getMessage());
                if (attempt < MAX_ATTEMPTS - 1) {
                    try {
                        Thread.sleep(2000L * (attempt + 1));
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("LLM 重试被中断", ie);
                    }
                }
            }
        }
        throw new IllegalStateException("Planner LLM 调用三次均失败: " + (last == null ? "?" : last.getMessage()));
    }

    public String getModel() {
        return model;
    }

    private String parseContent(String resp) {
        JsonNode root = objectMapper.readTree(resp);
        JsonNode choice = root.path("choices").path(0);
        String finish = choice.path("finish_reason").asText("");
        if ("length".equals(finish)) {
            throw new IllegalStateException("LLM 输出被 max_tokens 截断（finish_reason=length）");
        }
        JsonNode content = choice.path("message").path("content");
        if (content.isMissingNode() || content.asText().isBlank()) {
            throw new IllegalStateException("LLM 响应缺少 choices[0].message.content");
        }
        return content.asText().trim();
    }
}
