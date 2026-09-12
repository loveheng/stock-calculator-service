package com.zzh.stock_calculator.monitor;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * pipeline 巡检装配：管理 API 专用 RestClient（连接 3s/读 5s 快速失败，
 * 巡检失败本身即告警信号，不允许长阻塞巡检线程）。
 */
@Configuration
@EnableConfigurationProperties(PipelineWatchProperties.class)
public class PipelineWatchConfig {

    @Bean
    public RestClient pipelineMgmtRestClient() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(Duration.ofSeconds(5));
        return RestClient.builder().requestFactory(factory).build();
    }
}
