package com.zzh.stock_calculator.data.config;

import com.zzh.llm.LlmRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * KG 抽取 worker 装配（docs/ai-pipeline/cls-news-kg.md §8/§12）：与公告 worker 同款
 * @ConditionalOnProperty；chat 模型改经 LlmRegistry（ai.tiers.openai-mini，stock-calculator-llm 组件），
 * 原手搓 OpenAiChatOptions 装配（temperature/maxTokens/timeout）全部收进 tier 配置。
 * 结构化输出由 worker 侧 BeanOutputConverter 承担（Spring AI 官方 structured output 工具）。
 * 三键缺失 fail-fast（worker 无降级语义，registry.requireSpec 构建期即抛）。
 */
@Slf4j
@Configuration
@ConditionalOnProperty(
    prefix = "datasvc.worker",
    name = "enabled",
    havingValue = "true"
)
public class KgWorkerConfig {

    /** KG 抽取专用 chat 模型（openai-mini 档配置承载低温/maxTokens/timeout，lessons「LLM 网关」） */
    @Bean
    public OpenAiChatModel kgChatModel(LlmRegistry llmRegistry) {
        return llmRegistry.chatModel(com.zzh.llm.LlmTiers.MINI);
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
