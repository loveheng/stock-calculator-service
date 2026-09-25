package com.zzh.stock_calculator.mcp.quote;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * 行情 RestClient（config 复制原则：mcp 自持小份，不与 main 共享）。
 */
@Configuration
public class QuoteConfig {

    /** 出口频控参数（free-canvas v3 §8.3）：全局 ≤5 QPS + burst 20，随压测调整 */
    @Value("${quote.tencent.rate-limit.qps:5}")
    private double rateLimitQps;

    @Value("${quote.tencent.rate-limit.burst:20}")
    private int rateLimitBurst;

    @Value("${quote.tencent.rate-limit.max-wait-seconds:4}")
    private long rateLimitMaxWaitSeconds;

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
    public TencentDailyClient tencentDailyClient(RestClient.Builder quoteRestClientBuilder) {
        return new TencentDailyClient(quoteRestClientBuilder);
    }

    /** 业务注入口：频控装饰后的客户端（@Primary 消除与裸腾讯 bean 的双候选歧义） */
    @Bean
    @Primary
    public DailyQuoteClient dailyQuoteClient(TencentDailyClient tencentDailyClient) {
        return new RateLimitedQuoteClient(tencentDailyClient, new QuoteRateLimiter(
                rateLimitBurst, rateLimitQps, Duration.ofSeconds(rateLimitMaxWaitSeconds)));
    }
}
