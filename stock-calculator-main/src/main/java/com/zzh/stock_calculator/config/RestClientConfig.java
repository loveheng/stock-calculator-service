package com.zzh.stock_calculator.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

@Configuration
public class RestClientConfig {

    /**
     * 基础 ClientHttpRequestFactory（统一连接与读取超时）。
     * 注：Spring Boot 4 不再自动配置 RestClient.Builder Bean，且 org.springframework.boot.web.client
     * 包在 4.1 中已不存在（故无 RestClientCustomizer），此处直接用 RestClient.builder() 构建具体实例。
     */
    private SimpleClientHttpRequestFactory requestFactory() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(15));
        return factory;
    }

    /**
     * 全局通用的 RestClient（用于没有固定 baseUrl 的临时请求）。
     * @Primary 供 CommonHttpService 按类型注入（原 geminiRestClient 已随 native 模块删除）。
     */
    @Bean
    @Primary
    public RestClient commonRestClient() {
        return RestClient.builder()
                .requestFactory(requestFactory())
                .build();
    }
}