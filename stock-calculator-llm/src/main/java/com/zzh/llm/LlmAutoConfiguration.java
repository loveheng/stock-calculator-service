package com.zzh.llm;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * LLM tier 设施自动装配：提供 {@link LlmRegistry} 单例。
 * 惰性构造语义下注册表本身零开销，未配置 ai.tiers.* 的服务引入依赖也无副作用。
 */
@AutoConfiguration
@EnableConfigurationProperties(LlmTierProperties.class)
public class LlmAutoConfiguration {

    @Bean
    public LlmRegistry llmRegistry(LlmTierProperties properties) {
        return new LlmRegistry(properties);
    }
}
