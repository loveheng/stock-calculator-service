package com.zzh.stock_calculator.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class GeminiRestClientConfig {

    /**
     * Gemini 客户端 Bean。Spring Boot 4 不再自动配置 RestClient.Builder Bean，
     * 因此直接使用 RestClient.builder() 构建（普通 REST 调用，不依赖 Spring AI）。
     */
    @Bean("geminiRestClient")
    public RestClient geminiRestClient(
            @Value("${gemini.api-key}") String apiKey,
            @Value("${gemini.base-url:https://generativelanguage.googleapis.com/v1beta}") String baseUrl) {
        return RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("x-goog-api-key", apiKey)
                .build();
    }
}