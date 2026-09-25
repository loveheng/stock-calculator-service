package com.zzh.stock_calculator.mcp.kb;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * OpenAI 兼容 /chat/completions 原生 REST 客户端（persona 提炼离线一次性调用，
 * 照 KbEmbeddingClient 直调先例：HTTP 逻辑手搓不换 Spring AI，仅配置换轨）。
 * 连接三键读 ai.tiers.openai-mini（stock-calculator-llm 组件，env 用 OPENAI_MINI_* 三键）。
 * 429/5xx 重试 2 次退避 2s；response_format=json_object 由 DeepSeek 侧保证 JSON 输出。
 */
@Slf4j
@Component
public class KbLlmClient {

    private static final int MAX_ATTEMPTS = 3;

    /** persona 提炼属廉价离线批量 = openai-mini 档 */
    private static final String TIER = com.zzh.llm.LlmTiers.MINI;

    private final RestClient restClient;
    private final String baseUrl;
    private final String model;
    private final String apiToken;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public KbLlmClient(RestClient.Builder builder, com.zzh.llm.LlmRegistry llmRegistry) {
        // 显式 HTTP/1.1 工厂 + 长读超时：默认 JDK HttpClient 走 HTTP/2 时对部分
        // OpenAI 兼容网关（SiliconFlow 实测）抛 "Request cancelled"，且 persona
        // 长语料生成本身需要分钟级读超时，不能吃默认值
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10_000);
        factory.setReadTimeout(600_000);
        this.restClient = builder.requestFactory(factory).build();
        // 宽松语义（沿旧）：未配置不阻启动，调用时报错
        if (llmRegistry.isReady(TIER)) {
            com.zzh.llm.TierSpec spec = llmRegistry.spec(TIER);
            this.baseUrl = spec.getBaseUrl().replaceAll("/+$", "");
            this.model = spec.getModel();
            this.apiToken = spec.getApiKey();
        } else {
            log.warn("ai.tiers.openai-mini 三键未配齐：persona 提炼不可用（admin 端点将报错）");
            this.baseUrl = "";
            this.model = "";
            this.apiToken = "";
        }
    }

    /** 单轮对话（system + user），返回 choices[0].message.content 文本 */
    public String chat(String systemPrompt, String userMessage) {
        if (baseUrl.isBlank() || apiToken == null || apiToken.isBlank()) {
            throw new IllegalStateException("LLM 未配置（ai.tiers.openai-mini 三键，env 用 OPENAI_MINI_* 三键）");
        }
        String body = "{\"model\":\"" + model + "\",\"stream\":false,"
                // 部分网关/模型默认 max_tokens 很小，长卡 JSON 会被静默截断成非法 JSON；
                // 值取 8192：实测托管模型（Xing4.0-29B）长卡输出可超 4096，截断即非法 JSON
                + "\"max_tokens\":8192,"
                + "\"response_format\":{\"type\":\"json_object\"},"
                + "\"messages\":["
                + "{\"role\":\"system\",\"content\":" + objectMapper.writeValueAsString(systemPrompt) + "},"
                + "{\"role\":\"user\",\"content\":" + objectMapper.writeValueAsString(userMessage) + "}]}";
        RuntimeException last = null;
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            try {
                // byte[] 收包自行 UTF-8 解码：部分网关回 content-type=application/octet-stream
                // 且不带 charset，String 转换器会拒收（实测 SiliconFlow）
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
                log.warn("LLM 调用失败（第 {} 次）: {}", attempt + 1, e.getMessage());
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
        throw new IllegalStateException("LLM 调用三次均失败: " + (last == null ? "?" : last.getMessage()));
    }

    private String parseContent(String resp) {
        JsonNode root = objectMapper.readTree(resp);
        JsonNode choice = root.path("choices").path(0);
        // finish_reason=length 说明内容被 max_tokens 掐断：半截 JSON 后续必然解析失败，
        // 在此处给出明确根因而非让下游报 Jackson EOF
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

    public String getModel() {
        return model;
    }
}
