package com.zzh.stock_calculator.data.config;

import com.openai.client.OpenAIClient;
import com.zzh.stock_calculator.data.worker.EmbeddingRateLimiter;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.setup.OpenAiSetup;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * worker 角色向量化装配（设计文档 §4.5/§5，阶段 3 任务化）：与主服务 EmbeddingConfig
 * 同款的 CF 垫片路径（OpenAiSetup 同一公开静态方法 + maxRetries=0 防 429 被吞 +
 * CfUsageFixingClient 修 usage 缺失），但零 VectorStore/JPA——worker 只算不存（D2）。
 *
 * <p>fail-fast：worker.enabled=true 而 CF 凭据缺失 → 启动即抛（worker 没有降级语义，
 * 半配置状态会把任务逐条烧成丢弃）；区别于主服务的 R1 tripwire（主服务凭据缺失属
 * 合法形态——计算已移交 worker）。
 *
 * <p>监听器工厂：手动 ack（§4.2）+ prefetch=8（§5，竞争消费副本伸缩的基本单位）；
 * 独立工厂而非全局 yml——阶段 4 公告任务 prefetch=2 需并存。
 */
@Configuration
@ConditionalOnProperty(prefix = "datasvc.worker", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(WorkerProperties.class)
public class WorkerEmbeddingConfig {

    @Bean
    public OpenAiEmbeddingModel workerEmbeddingModel(WorkerProperties props) {
        WorkerProperties.Embedding embedding = props.getEmbedding();
        String accountId = embedding.getAccountId();
        String apiToken = embedding.getApiToken();
        if (accountId == null || accountId.isBlank() || apiToken == null || apiToken.isBlank()) {
            throw new IllegalStateException(
                    "datasvc.worker.enabled=true 但 CLOUDFLARE_ACCOUNT_ID / CLOUDFLARE_API_TOKEN 未配置，"
                            + "worker 无降级语义，拒绝以半配置状态启动");
        }
        String baseUrl = "https://api.cloudflare.com/client/v4/accounts/"
                + accountId + "/ai/v1";
        // maxRetries=0：429 直抛 RateLimitException，交由消费端三分类分流（§6.1）
        OpenAIClient rawClient = OpenAiSetup.setupSyncClient(baseUrl, apiToken,
                null, null, null, null, false, false, embedding.getModel(),
                embedding.getReadTimeout(), 0, null, null,
                ObservationRegistry.NOOP, null, List.of());
        return OpenAiEmbeddingModel.builder()
                .openAiClient(new CfUsageFixingClient(rawClient))
                .options(OpenAiEmbeddingOptions.builder()
                        .model(embedding.getModel())
                        .dimensions(embedding.getDimensions())
                        .timeout(embedding.getReadTimeout())
                        .build())
                .build();
    }

    @Bean
    public EmbeddingRateLimiter workerEmbeddingRateLimiter(WorkerProperties props) {
        return new EmbeddingRateLimiter(props.getEmbedding().getRateLimitPerMinute());
    }

    @Bean
    public SimpleRabbitListenerContainerFactory embeddingWorkerListenerFactory(
            ConnectionFactory connectionFactory, WorkerProperties props) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        factory.setPrefetchCount(props.getPrefetch().getEmbedding());
        return factory;
    }
}
