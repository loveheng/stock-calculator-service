package com.zzh.stock_calculator.mcp.quote;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * 行情 RestClient（config 复制原则：mcp 自持小份，不与 main 共享）。
 */
@Configuration
public class QuoteConfig {

    @Bean
    public RestClient.Builder quoteRestClientBuilder() {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(10));
        return RestClient.builder().requestFactory(factory);
    }

    @Bean
    public EastmoneyDailyClient eastmoneyDailyClient(RestClient.Builder quoteRestClientBuilder) {
        return new EastmoneyDailyClient(quoteRestClientBuilder);
    }
}
