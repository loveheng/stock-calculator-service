package com.zzh.stock_calculator.llm.config;

import com.zzh.stock_calculator.util.HttpUtil;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * LLM 组件配置：注册 LlmProperties（llm 前缀）并声明**全局模型 Bean**。
 * 每个渠道一个 OpenAiChatModel 实例，连接参数（base-url/api-key/model/超时/maxRetries）
 * 全部落在 OpenAiChatOptions 上——不显式传 openAiClient 时 build() 会按 options
 * 自动装配底层 OkHttp 客户端（Spring AI 2.x 标准做法）。
 * bean 方法按 base-url 是否配置做条件装配：未配置时不建 Bean，渠道服务经
 * ObjectProvider 注入后健康检查自动跳过，不影响其余渠道。
 * geminiChatModel 标 @Primary：vision 旧链路（/ocr-parse 的 ChatClient.Builder）按唯一
 * ChatModel 解析时复用同一实例，Gemini 连接参数因此全工程只有 llm.gemini.* 一处。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(LlmProperties.class)
public class LlmConfig {

    /** Gemini 渠道模型（全局 Bean，@Primary 供旧视觉链路 ChatClient 复用） */
    @Bean("geminiChatModel")
    @Primary
    @ConditionalOnProperty(prefix = "llm.gemini", name = "base-url")
    public OpenAiChatModel geminiChatModel(LlmProperties properties) {
        return buildChatModel(properties.getGemini());
    }

    /** Groq 渠道模型（全局 Bean） */
    @Bean("groqChatModel")
    @ConditionalOnProperty(prefix = "llm.groq", name = "base-url")
    public OpenAiChatModel groqChatModel(LlmProperties properties) {
        return buildChatModel(properties.getGroq());
    }

    /** 渠道专属 ChatModel：连接参数全部落在 OpenAiChatOptions 上；maxRetries=0 保住责任链快速流转语义 */
    public static OpenAiChatModel buildChatModel(LlmProperties.Provider props) {
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
