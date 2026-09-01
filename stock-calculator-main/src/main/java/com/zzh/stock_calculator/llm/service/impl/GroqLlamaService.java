package com.zzh.stock_calculator.llm.service.impl;

import com.zzh.stock_calculator.llm.config.LlmProperties;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Groq 渠道（备用策略，@Order(2)）：Groq 的 OpenAI 兼容端点（Llama 开源模型极速推理），
 * 注入全局模型 Bean groqChatModel（连接参数见 llm.groq.*），错误分类复用基类模板。
 */
@Component
@Order(1)
public class GroqLlamaService extends AbstractOpenAiCompatibleLlmService {

    public GroqLlamaService(LlmProperties properties,
            @Qualifier("groqChatModel") ObjectProvider<OpenAiChatModel> chatModel) {
        super("groq", properties.getGroq(), chatModel);
    }
}
