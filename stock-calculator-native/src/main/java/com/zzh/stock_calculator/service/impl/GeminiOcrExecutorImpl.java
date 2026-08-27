package com.zzh.stock_calculator.service.impl;

import com.zzh.stock_calculator.common.BusinessException;
import com.zzh.stock_calculator.service.OcrExecutor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Base64;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class GeminiOcrExecutorImpl implements OcrExecutor {

    private final RestClient geminiRestClient;
    private final ObjectMapper objectMapper;
    private final String model;

    public GeminiOcrExecutorImpl(
            @Qualifier("geminiRestClient") RestClient geminiRestClient,
            @Value("${gemini.model:gemini-3.6-flash}") String model,
            ObjectMapper objectMapper) {
        this.model = model;
        this.objectMapper = objectMapper;
        this.geminiRestClient = geminiRestClient;
    }

    @Override
    @Cacheable(value = "genericVisionCache", key = "#cacheKey")
    public <T> T execute(String cacheKey, byte[] imageBytes, String systemPrompt, TypeReference<T> typeRef) {
        log.info("未命中视觉缓存，调用多模态大模型解析 (CacheKey={})...", cacheKey);
        String rawText = callVisionModel(imageBytes, systemPrompt);
        return parseJson(rawText, typeRef);
    }

    @Override
    @Cacheable(value = "genericVisionCache", key = "#cacheKey")
    public <T> T execute(String cacheKey, byte[] imageBytes, String systemPrompt, Class<T> clazz) {
        log.info("未命中视觉缓存，调用多模态大模型解析 (CacheKey={})...", cacheKey);
        String rawText = callVisionModel(imageBytes, systemPrompt);
        return parseJson(rawText, clazz);
    }

    /**
     * 通过普通 REST 接口调用 Gemini generateContent 完成多模态识别。
     */
    private String callVisionModel(byte[] imageBytes, String promptText) {
        try {
            Map<String, Object> requestBody = Map.of(
                    "contents", List.of(Map.of(
                            "parts", List.of(
                                    Map.of("text", promptText),
                                    Map.of("inlineData", Map.of(
                                            "mimeType", "image/jpeg",
                                            "data", Base64.getEncoder().encodeToString(imageBytes))))
                    )),
                    "generationConfig", Map.of("temperature", 0.0)
            );

            String responseBody = geminiRestClient.post()
                    .uri("/models/{model}:generateContent", model)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestBody)
                    .retrieve()
                    .body(String.class);

            return extractText(responseBody);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("调用多模态视觉模型失败", e);
            throw new BusinessException(500, "图像内容解析异常: " + e.getMessage());
        }
    }

    private String extractText(String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode candidates = root.path("candidates");
            if (candidates.isMissingNode() || candidates.size() == 0) {
                throw new BusinessException(500, "多模态大模型未返回有效数据");
            }
            JsonNode parts = candidates.get(0).path("content").path("parts");
            StringBuilder sb = new StringBuilder();
            for (JsonNode part : parts) {
                sb.append(part.path("text").asText());
            }
            if (sb.length() == 0) {
                throw new BusinessException(500, "多模态大模型未返回有效文本");
            }
            return sb.toString();
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("解析模型响应失败", e);
            throw new BusinessException(500, "解析模型响应失败: " + e.getMessage());
        }
    }

    private <T> T parseJson(String rawText, TypeReference<T> typeRef) {
        String cleanJson = cleanMarkdown(rawText);
        try {
            return objectMapper.readValue(cleanJson, typeRef);
        } catch (Exception e) {
            log.error("通用视觉 JSON 反序列化失败: rawText={}", rawText, e);
            throw new BusinessException(500, "数据解析失败，模型返回格式不合规");
        }
    }

    private <T> T parseJson(String rawText, Class<T> clazz) {
        String cleanJson = cleanMarkdown(rawText);
        try {
            return objectMapper.readValue(cleanJson, clazz);
        } catch (Exception e) {
            log.error("通用视觉 JSON 反序列化失败: rawText={}", rawText, e);
            throw new BusinessException(500, "数据解析失败，模型返回格式不合规");
        }
    }

    private String cleanMarkdown(String text) {
        if (text == null || text.isBlank()) {
            return "{}";
        }
        String clean = text.trim();
        if (clean.startsWith("```json")) {
            clean = clean.substring(7);
        } else if (clean.startsWith("```")) {
            clean = clean.substring(3);
        }
        if (clean.endsWith("```")) {
            clean = clean.substring(0, clean.length() - 3);
        }
        return clean.trim();
    }
}