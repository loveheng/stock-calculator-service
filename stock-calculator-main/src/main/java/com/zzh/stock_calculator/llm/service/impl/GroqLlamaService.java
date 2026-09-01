package com.zzh.stock_calculator.llm.service.impl;

import com.zzh.stock_calculator.llm.config.LlmProperties;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Groq 渠道（备用策略，@Order(2)）。
 * 走 Groq 的 OpenAI 兼容端点（/openai/v1/chat/completions），极速推理开源模型（默认 llama-3.3-70b）。
 * 免费层可用模型会轮换下线，model 必须保持配置化（llm.groq.model）。
 */
@Component
@Order(1)
public class GroqLlamaService extends AbstractOpenAiCompatibleLlmService {

    public GroqLlamaService(LlmProperties properties, ObjectMapper objectMapper) {
        super("groq", properties.getGroq(), objectMapper);
    }
}
