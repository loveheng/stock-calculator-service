package com.zzh.stock_calculator.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.zzh.stock_calculator.service.OcrExecutor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzh.stock_calculator.common.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeTypeUtils;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class GeminiOcrExecutorImpl implements OcrExecutor {

    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;

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

    private String callVisionModel(byte[] imageBytes, String promptText) {
        try {
            ByteArrayResource resource = new ByteArrayResource(imageBytes);

            String content = chatClient.prompt()
                    .user(u -> u.text(promptText)
                            .media(MimeTypeUtils.IMAGE_JPEG, resource))
                    .call()
                    .content();

            if (content == null || content.isBlank()) {
                throw new BusinessException(500, "多模态大模型未返回有效数据");
            }
            return content;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("调用多模态视觉模型失败", e);
            throw new BusinessException(500, "图像内容解析异常: " + e.getMessage());
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
