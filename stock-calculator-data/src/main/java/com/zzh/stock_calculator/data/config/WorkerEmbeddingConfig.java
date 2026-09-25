package com.zzh.stock_calculator.data.config;

import com.zzh.stock_calculator.data.worker.EmbeddingRateLimiter;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * worker 角色向量化装配（设计文档 §4.5/§5，阶段 3 任务化）：embedding 模型经
 * LlmRegistry（ai.embeddings.embed，stock-calculator-llm 组件）装配——provider 可切换
 * （cloudflare 走 CF 垫片 + usage 修复 / openai 走通用兼容端点），registry 内已封装。
 * 零 VectorStore/JPA——worker 只算不存（D2）。
 *
 * <p>fail-fast：worker.enabled=true 而 embed tier 凭据缺失 → 启动即抛（worker 没有
 * 降级语义，半配置状态会把任务逐条烧成丢弃）；区别于主服务的 R1 tripwire（主服务
 * 凭据缺失属合法形态——计算已移交 worker）。
 *
 * <p>监听器工厂：手动 ack（§4.2）+ prefetch=8（§5，竞争消费副本伸缩的基本单位）；
 * 独立工厂而非全局 yml——阶段 4 公告任务 prefetch=2 需并存。
 */
@Configuration
@ConditionalOnProperty(
    prefix = "datasvc.worker",
    name = "enabled",
    havingValue = "true"
)
@EnableConfigurationProperties(WorkerProperties.class)
public class WorkerEmbeddingConfig {

    @Bean
    public OpenAiEmbeddingModel workerEmbeddingModel(com.zzh.llm.LlmRegistry llmRegistry) {
        // registry.requireSpec 式 fail-fast：凭据缺失启动即抛（worker 无降级语义）
        return llmRegistry.embeddingModel(com.zzh.llm.LlmTiers.EMBED);
    }

    @Bean
    public EmbeddingRateLimiter workerEmbeddingRateLimiter(
        WorkerProperties props
    ) {
        return new EmbeddingRateLimiter(
            props.getEmbedding().getRateLimitPerMinute()
        );
    }

    @Bean
    public SimpleRabbitListenerContainerFactory embeddingWorkerListenerFactory(
        ConnectionFactory connectionFactory,
        WorkerProperties props
    ) {
        SimpleRabbitListenerContainerFactory factory =
            new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        factory.setPrefetchCount(props.getPrefetch().getEmbedding());
        return factory;
    }

    @Bean
    public SimpleRabbitListenerContainerFactory memoryWorkerListenerFactory(
        ConnectionFactory connectionFactory,
        WorkerProperties props
    ) {
        SimpleRabbitListenerContainerFactory factory =
            new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        factory.setPrefetchCount(1); // memory 任务较重，单条处理
        return factory;
    }
}
