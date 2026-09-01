package com.zzh.stock_calculator.llm.service.impl;

import com.zzh.stock_calculator.llm.config.LlmProperties;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Gemini 渠道（首选策略，@Order(1)）：Google Gemini 的 OpenAI 兼容端点，
 * 注入全局模型 Bean geminiChatModel（连接参数见 llm.gemini.*），错误分类复用基类模板。
 */
@Component
@Order(2)
public class GeminiLlmService extends AbstractOpenAiCompatibleLlmService {

    public GeminiLlmService(LlmProperties properties,
            @Qualifier("geminiChatModel") ObjectProvider<OpenAiChatModel> chatModel) {
        super("gemini", properties.getGemini(), chatModel);
    }
}
