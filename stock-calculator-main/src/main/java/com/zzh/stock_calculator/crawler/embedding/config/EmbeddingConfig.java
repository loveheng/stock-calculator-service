package com.zzh.stock_calculator.crawler.embedding.config;

import com.openai.client.OpenAIClient;
import com.zzh.stock_calculator.crawler.embedding.service.EmbeddingQuotaGuard;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.setup.OpenAiSetup;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

/**
 * 向量化 Bean 装配（设计文档 §4.1/§4.2，Option A 垫片方案）。
 *
 * <p>OpenAiEmbeddingModel：底层 client 经 Spring AI 自带的 OpenAiSetup.setupSyncClient
 * 构建（公开静态方法，与框架内部同一路径），maxRetries=0 —— openai-java 内置重试会
 * 吞掉 429 破坏熔断分类，必须显式关闭；随后包一层 CfUsageFixingClient 修 CF 响应缺
 * usage 字段的兼容问题。
 *
 * <p>PgVectorStore：手动 builder 装配（与 DeepSeekConfig 手动装配同法），初始化时自动
 * 建扩展（vector/hstore/uuid-ossp）、vector_store 表（vector(1024)）与 HNSW(cosine) 索引。
 *
 * <p>R1 装配语义：本配置类不再使用构建期 {@code @Conditional}（AOT 会在 native 构建
 * 期固化判定导致 Bean 被裁剪），改为 Bean 定义一律注册，实例化交给全局
 * lazy-initialization + EmbeddingGate 运行期门控——未启用时无调用方触发实例化，
 * native/JVM 行为一致。embeddingModel() 内置防御性 tripwire：门控未通过时被
 * 意外实例化立即抛出明确异常，避免落到凭据层报 401 之类的次生错误。
 */
@Configuration
@EnableConfigurationProperties(EmbeddingProperties.class)
public class EmbeddingConfig {

    @Bean
    public OpenAiEmbeddingModel embeddingModel(EmbeddingProperties props, EmbeddingGate gate) {
        if (!gate.isAvailable()) {
            throw new IllegalStateException(
                    "embedding 未启用或 CF 凭据缺失（embedding.enabled / account-id / api-token），"
                            + "EmbeddingModel 不应被实例化；调用方必须先通过 EmbeddingGate 门控");
        }
        String baseUrl = "https://api.cloudflare.com/client/v4/accounts/"
                + props.getCloudflare().getAccountId() + "/ai/v1";
        // maxRetries=0：429 直抛 RateLimitException，交由 EmbeddingQuotaGuard 熔断分类
        OpenAIClient rawClient = OpenAiSetup.setupSyncClient(baseUrl, props.getCloudflare().getApiToken(),
                null, null, null, null, false, false, props.getCloudflare().getModel(),
                props.getCloudflare().getReadTimeout(), 0, null, null,
                ObservationRegistry.NOOP, null, List.of());
        return OpenAiEmbeddingModel.builder()
                .openAiClient(new CfUsageFixingClient(rawClient))
                .options(OpenAiEmbeddingOptions.builder()
                        .model(props.getCloudflare().getModel())
                        .dimensions(props.getCloudflare().getDimensions())
                        .timeout(props.getCloudflare().getReadTimeout())
                        .build())
                .build();
    }

    @Bean
    public PgVectorStore vectorStore(JdbcTemplate jdbcTemplate, EmbeddingModel embeddingModel,
                                     EmbeddingProperties props) {
        return PgVectorStore.builder(jdbcTemplate, embeddingModel)
                .dimensions(props.getCloudflare().getDimensions())
                .distanceType(PgVectorStore.PgDistanceType.COSINE_DISTANCE)
                .indexType(PgVectorStore.PgIndexType.HNSW)
                .initializeSchema(true)
                .build();
    }

    @Bean
    @ConditionalOnMissingBean
    public EmbeddingQuotaGuard embeddingQuotaGuard(EmbeddingProperties props) {
        return new EmbeddingQuotaGuard(props.getDailyMaxArticles());
    }
}
