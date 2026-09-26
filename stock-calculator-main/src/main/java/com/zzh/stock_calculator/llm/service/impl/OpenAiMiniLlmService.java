package com.zzh.stock_calculator.llm.service.impl;

import com.zzh.stock_calculator.llm.config.LlmProperties;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * openai-mini 渠道（首选策略，@Order(1)）：廉价批量档（硅基流动 Qwen 等 OpenAI 兼容端点），
 * 承担图片交易流水规整 / 文本解析，替代 gemini；注入全局模型 Bean openAiMiniChatModel
 * （连接参数见 llm.openai-mini.*，与 .env 的 OPENAI_MINI_* 三键对齐），错误分类复用基类模板。
 */
@Component
@Order(1)
public class OpenAiMiniLlmService extends AbstractOpenAiCompatibleLlmService {

    public OpenAiMiniLlmService(LlmProperties properties,
            @Qualifier("openAiMiniChatModel") ObjectProvider<OpenAiChatModel> chatModel) {
        super("openai-mini", properties.getOpenaiMini(), chatModel);
    }
}
