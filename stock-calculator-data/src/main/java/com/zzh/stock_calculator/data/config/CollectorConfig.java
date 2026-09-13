package com.zzh.stock_calculator.data.config;

import com.zzh.stock_calculator.data.announcement.CninfoClient;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * collector 角色装配（设计文档 §5/§8 阶段 4 任务 2）：CNINFO RestClient + 客户端 +
 * 控制面监听器工厂（手动 ack + prefetch=1，快照覆盖式单发单收）。
 * datasvc.collector.enabled=false（worker 部署）不装配——拉取面与计算面隔离（D4）。
 */
@Configuration
@ConditionalOnProperty(prefix = "datasvc.collector", name = "enabled", havingValue = "true")
@EnableConfigurationProperties({CollectorProperties.class, PullLoopProperties.class})
public class CollectorConfig {

    /**
     * CNINFO 专用 RestClient（连接 5s/读 15s，与主服务 commonRestClient 同参数）。
     * 数据服务无共享 RestClient 场景，独立成 bean 不设 @Primary。
     */
    @Bean
    public RestClient cninfoRestClient() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(15));
        return RestClient.builder().requestFactory(factory).build();
    }

    @Bean
    public CninfoClient cninfoClient(RestClient cninfoRestClient, CollectorProperties properties) {
        return new CninfoClient(cninfoRestClient, properties);
    }

    /**
     * 控制面监听器工厂：手动 ack（§4.4）+ prefetch=1。控制队列无重试环
     * （快照丢失由下一次快照兜底，MqTopologyConfig），毒消息由消费端捕获后 ack 丢弃。
     */
    @Bean
    public SimpleRabbitListenerContainerFactory collectorControlListenerFactory(
            ConnectionFactory connectionFactory) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        factory.setPrefetchCount(1);
        return factory;
    }
}
