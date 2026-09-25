package com.zzh.stock_calculator.crawler.embedding.config;

import com.openai.client.OpenAIClient;
import com.zzh.stock_calculator.crawler.EmbeddingQuotaGuard;
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
 * 向量化 Bean 装配（设计文档 §4.1/§4.2）：供应商连接规格已迁 ai.embeddings.embed
 * （stock-calculator-llm 组件，provider 可切换 cloudflare | openai，不写死任何一家）；
 * CF 垫片 URL 拼接与 CfUsageFixingClient usage 修复包装收编进 llm 模块由 registry 分派。
 *
 * <p>PgVectorStore：手动 builder 装配，初始化时自动建扩展（vector/hstore/uuid-ossp）、
 * vector_store 表（vector(1024)）与 HNSW(cosine) 索引。
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
    public OpenAiEmbeddingModel embeddingModel(EmbeddingProperties props, EmbeddingGate gate,
                                               com.zzh.llm.LlmRegistry llmRegistry) {
        if (!gate.isAvailable()) {
            throw new IllegalStateException(
                    "embedding 未启用或 ai.embeddings.embed 凭据缺失（embedding.enabled / "
                            + "provider 对应的 account-id 或 base-url / api-token / model），"
                            + "EmbeddingModel 不应被实例化；调用方必须先通过 EmbeddingGate 门控");
        }
        // registry 内建 maxRetries 缺省 0：429 直抛 RateLimitException，交由 EmbeddingQuotaGuard 熔断分类
        return llmRegistry.embeddingModel(com.zzh.llm.LlmTiers.EMBED);
    }

    @Bean
    public PgVectorStore vectorStore(JdbcTemplate jdbcTemplate, EmbeddingModel embeddingModel,
                                     com.zzh.llm.LlmRegistry llmRegistry) {
        return PgVectorStore.builder(jdbcTemplate, embeddingModel)
                .dimensions(llmRegistry.embedSpec(com.zzh.llm.LlmTiers.EMBED).getDimensions())
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
