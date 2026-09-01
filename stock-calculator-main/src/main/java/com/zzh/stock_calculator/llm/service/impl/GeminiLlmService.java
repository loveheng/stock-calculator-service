package com.zzh.stock_calculator.llm.service.impl;

import com.zzh.stock_calculator.llm.config.LlmProperties;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Google Gemini 渠道（首选策略，@Order(1)）。
 * 走 Gemini 的 OpenAI 兼容端点（/v1beta/openai/chat/completions），与项目 spring.ai 同一 Key 与网关，
 * 免费层突发 RPM 限流时由调度器自动降级 Groq。
 */
@Component
@Order(2)
public class GeminiLlmService extends AbstractOpenAiCompatibleLlmService {

    public GeminiLlmService(LlmProperties properties, ObjectMapper objectMapper) {
        super("gemini", properties.getGemini(), objectMapper);
    }
}
