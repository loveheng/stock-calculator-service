package com.zzh.stock_calculator.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

@Configuration
public class RestClientConfig {

    /**
     * 基础 ClientHttpRequestFactory 配置（统一连接与读取超时）
     */
    private SimpleClientHttpRequestFactory requestFactory() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(15));
        return factory;
    }

    /**
     * 1. 全局通用的 RestClient（用于没有固定 baseUrl 的临时请求）
     */
    @Bean
    @Primary
    public RestClient commonRestClient(RestClient.Builder builder) {
        return builder
                .requestFactory(requestFactory())
                .build();
    }

    /**
     * 2. 图像处理专用 RestClient（绑定 imgproxy / imaginary 的 baseUrl）
     */
    @Bean("imageRestClient")
    public RestClient imageRestClient(
            RestClient.Builder builder,
            @Value("${image.service.url:http://localhost:8088}") String imageBaseUrl) {
        return builder
                .baseUrl(imageBaseUrl)
                .requestFactory(requestFactory())
                .build();
    }
}
