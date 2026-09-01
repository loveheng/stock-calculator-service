package com.zzh.stock_calculator.llm.service.impl;

import com.zzh.stock_calculator.llm.config.LlmProperties;
import com.zzh.stock_calculator.llm.service.LlmProviderException;
import com.zzh.stock_calculator.llm.service.LlmService;
import com.zzh.stock_calculator.util.HttpUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI 兼容协议基类：Gemini（/v1beta/openai）与 Groq（/openai/v1）的
 * 请求体、响应体、错误结构完全同构（POST /chat/completions, Bearer 鉴权），
 * 子类只差 base-url / api-key / model 三项配置。
 * 请求/响应 JSON 均用 ObjectMapper 手动序列化解析，不依赖 RestClient 的转换器自动探测。
 * 错误判定：429（含 Retry-After 提示）、5xx、连接/读取超时 → 可重试；
 * 401/403（Key 无效）与响应结构异常 → 不可重试，直接换渠道。
 */
@Slf4j
public abstract class AbstractOpenAiCompatibleLlmService implements LlmService {

    private final String providerName;
    private final LlmProperties.Provider props;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    protected AbstractOpenAiCompatibleLlmService(String providerName,
                                                 LlmProperties.Provider props,
                                                 ObjectMapper objectMapper) {
        this.providerName = providerName;
        this.props = props;
        this.objectMapper = objectMapper;
        // 客户端构建统一收敛在 HttpUtil
        this.restClient = HttpUtil.jdkRestClient(props.getConnectTimeout(), props.getReadTimeout());
    }

    @Override
    public String providerName() {
        return providerName;
    }

    @Override
    public boolean isAvailable() {
        return props.isEnabled()
                && StringUtils.hasText(props.getApiKey())
                && StringUtils.hasText(props.getBaseUrl());
    }

    @Override
    public String chat(String systemPrompt, String userMessage) {
        try {
            Map<String, Object> request = new LinkedHashMap<>();
            request.put("model", props.getModel());
            request.put("messages", List.of(
                    Map.of("role", "system", "content", systemPrompt),
                    Map.of("role", "user", "content", userMessage)));
            request.put("temperature", 0);
            request.put("stream", false);

            byte[] respBytes = restClient.post()
                    .uri(HttpUtil.trimTrailingSlash(props.getBaseUrl()) + "/chat/completions")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + props.getApiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(objectMapper.writeValueAsBytes(request))
                    .retrieve()
                    .body(byte[].class);

            return parseContent(HttpUtil.toUtf8String(respBytes));

        } catch (RestClientResponseException e) {
            throw classify(e);
        } catch (LlmProviderException e) {
            throw e;
        } catch (Exception e) {
            // 连接/读取超时与其它网络 IO 统一按可重试处理
            throw new LlmProviderException(providerName + " 请求异常: " + HttpUtil.rootMessage(e), true, e);
        }
    }

    /** 解析 OpenAI 兼容响应 choices[0].message.content；content 为空返回 ""（业务空结果） */
    private String parseContent(String body) {
        if (!StringUtils.hasText(body)) {
            throw new LlmProviderException(providerName + " 返回空响应", true);
        }
        Map<String, Object> json = readJson(body);
        // 部分网关会以 200 + error 体返回失败，优先检查
        if (json.get("error") instanceof Map<?, ?> error) {
            throw new LlmProviderException(providerName + " 返回错误: " + error.get("message"), true);
        }
        if (json.get("choices") instanceof List<?> choices && !choices.isEmpty()
                && choices.getFirst() instanceof Map<?, ?> first
                && first.get("message") instanceof Map<?, ?> message) {
            Object content = message.get("content");
            return content == null ? "" : String.valueOf(content).trim();
        }
        throw new LlmProviderException(providerName + " 响应缺少 choices.message.content", true);
    }

    private LlmProviderException classify(RestClientResponseException e) {
        int status = e.getStatusCode().value();
        if (status == 429) {
            String retryAfter = e.getResponseHeaders().getFirst(HttpHeaders.RETRY_AFTER);
            return new LlmProviderException(providerName + " 触发限流, http=429"
                    + (retryAfter != null ? ", retry-after=" + retryAfter : ""), true, e);
        }
        if (status >= 500) {
            return new LlmProviderException(providerName + " 服务端异常, http=" + status, true, e);
        }
        if (status == 401 || status == 403) {
            return new LlmProviderException(providerName + " 鉴权失败(Key 无效或过期), http=" + status, false, e);
        }
        return new LlmProviderException(providerName + " 请求被拒绝, http=" + status, false, e);
    }

    private Map<String, Object> readJson(String body) {
        try {
            return objectMapper.readValue(body, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            throw new LlmProviderException(providerName + " 响应非 JSON", true, e);
        }
    }
}
