package com.zzh.stock_calculator.copilot.config;

import com.zzh.stock_calculator.util.HttpUtil;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * DeepSeek 聊天模型 Bean 配置（copilot 问答专用，付费渠道）。
 * 不进 llm 责任链（gemini/groq 免费渠道留给引流功能，互不混用）。
 * 通过 @ConditionalOnProperty 控制装配：base-url 未配置时不建 Bean，
 * 调用方（AiChatOrchestrationService）经 ObjectProvider 容错并降级 503。
 * maxRetries=0：429/5xx 由调用方映射为 BusinessException，不在 SDK 内静默重试。
 */
@Configuration
@EnableConfigurationProperties(DeepSeekProperties.class)
public class DeepSeekConfig {

    @Bean("deepSeekChatModel")
    @ConditionalOnProperty(prefix = "copilot.llm.deepseek", name = "base-url")
    public OpenAiChatModel deepSeekChatModel(DeepSeekProperties props) {
        return OpenAiChatModel.builder()
                .options(OpenAiChatOptions.builder()
                        .model(props.getModel())
                        .baseUrl(HttpUtil.trimTrailingSlash(props.getBaseUrl()))
                        .apiKey(props.getApiKey())
                        .temperature(0.0)
                        .timeout(props.getReadTimeout())
                        .maxRetries(0)
                        .build())
                .build();
    }
}
