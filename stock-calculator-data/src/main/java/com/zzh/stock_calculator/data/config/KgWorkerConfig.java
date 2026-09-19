package com.zzh.stock_calculator.data.config;

import com.zzh.stock_calculator.data.llm.LlmGatewayProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/**
 * KG 抽取 worker 装配（docs/ai-pipeline/cls-news-kg.md §8/§12）：与公告 worker 同款
 * @ConditionalOnProperty + LlmGatewayProperties 复用（datasvc.llm.*，单渠道 OpenAI 兼容端点，
 * gemini/groq 兼容 base-url 皆可），chat 模型装配与 main LlmConfig.buildChatModel 同构——
 * 连接参数全落 OpenAiChatOptions，maxRetries=0 让 openai-java 异常原样抛出交三分类分流。
 * 结构化输出由 worker 侧 BeanOutputConverter 承担（Spring AI 官方 structured output 工具）。
 * 配置缺失 fail-fast（worker 无降级语义，与 WorkerEmbeddingConfig/AnnouncementWorkerConfig 同约定）。
 */
@Slf4j
@Configuration
@ConditionalOnProperty(
    prefix = "datasvc.worker",
    name = "enabled",
    havingValue = "true"
)
@EnableConfigurationProperties(LlmGatewayProperties.class)
public class KgWorkerConfig {

    /** KG 抽取专用 chat 模型（低温 + 显式 maxTokens 防 JSON 腰斩，lessons「LLM 网关」） */
    @Bean
    public OpenAiChatModel kgChatModel(LlmGatewayProperties props) {
        if (
            !StringUtils.hasText(props.getBaseUrl()) ||
            !StringUtils.hasText(props.getApiKey()) ||
            !StringUtils.hasText(props.getModel())
        ) {
            throw new IllegalStateException(
                "datasvc.worker.enabled=true 但 datasvc.llm.base-url / api-key / model 未配置，" +
                    "KG 抽取无法执行，拒绝以半配置状态启动"
            );
        }
        return OpenAiChatModel.builder()
            .options(
                OpenAiChatOptions.builder()
                    .model(props.getModel())
                    .baseUrl(props.getBaseUrl())
                    .apiKey(props.getApiKey())
                    .temperature(0.2)
                    .timeout(props.getReadTimeout())
                    .maxRetries(0)
                    .maxTokens(props.getMaxTokens())
                    .build()
            )
            .build();
    }

    /** KG 任务监听器工厂：手动 ack + prefetch=2（每日小批量，公平轮转与公告 worker 对齐） */
    @Bean
    public SimpleRabbitListenerContainerFactory kgWorkerListenerFactory(
        ConnectionFactory connectionFactory
    ) {
        SimpleRabbitListenerContainerFactory factory =
            new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        factory.setPrefetchCount(2);
        return factory;
    }
}
