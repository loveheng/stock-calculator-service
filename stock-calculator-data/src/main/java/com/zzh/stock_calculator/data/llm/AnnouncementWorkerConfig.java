package com.zzh.stock_calculator.data.llm;

import com.zzh.stock_calculator.data.announcement.AnnouncementParseProperties;
import com.zzh.stock_calculator.data.announcement.CninfoPdfClient;
import com.zzh.stock_calculator.data.config.WorkerProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 公告处理 worker 装配（设计文档 §8 阶段 4 任务 4）：datasvc.worker.enabled=true 时
 * 装配精简 LLM 网关 + CNINFO PDF 下载客户端 + 独立监听器工厂（prefetch=2，与
 * embedding worker prefetch=8 并存，PDF+LLM 单任务耗时长，低预取保公平轮转）。
 * 配置缺失 fail-fast（worker 无降级语义，与 WorkerEmbeddingConfig 同款约定）。
 */
@Slf4j
@Configuration
@ConditionalOnProperty(prefix = "datasvc.worker", name = "enabled", havingValue = "true")
@EnableConfigurationProperties({LlmGatewayProperties.class, AnnouncementParseProperties.class})
public class AnnouncementWorkerConfig {

    /** OpenAI 兼容 chat-completions 网关：RestClient 单渠道（精简版 LlmChainRouter） */
    @Bean
    public LlmGateway llmGateway(LlmGatewayProperties props) {
        if (!StringUtils.hasText(props.getBaseUrl()) || !StringUtils.hasText(props.getApiKey())
                || !StringUtils.hasText(props.getModel())) {
            throw new IllegalStateException(
                    "datasvc.worker.enabled=true 但 datasvc.llm.base-url / api-key / model 未配置，"
                            + "公告蒸馏无法执行，拒绝以半配置状态启动");
        }
        RestClient client = RestClient.builder()
                .baseUrl(props.getBaseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + props.getApiKey())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
        return new LlmGateway(client, props);
    }

    /** CNINFO PDF 下载专用 RestClient（连接 5s/读 15s，与 collector 侧 cninfoRestClient 同参数） */
    @Bean
    public RestClient cninfoPdfRestClient() {
        org.springframework.http.client.SimpleClientHttpRequestFactory factory =
                new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(15));
        return RestClient.builder().requestFactory(factory).build();
    }

    /** CNINFO PDF 下载客户端（worker 处理链自持，2026-09-13 多副本改造自 CninfoClient 拆出） */
    @Bean
    public CninfoPdfClient cninfoPdfClient(RestClient cninfoPdfRestClient, AnnouncementParseProperties props) {
        return new CninfoPdfClient(cninfoPdfRestClient, props);
    }

    /** 公告任务监听器工厂：手动 ack + prefetch=2 + 并发消费者（独立工厂，非全局 yml） */
    @Bean
    public SimpleRabbitListenerContainerFactory announcementWorkerListenerFactory(
            ConnectionFactory connectionFactory, WorkerProperties props) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        factory.setPrefetchCount(props.getPrefetch().getAnnouncement());
        factory.setConcurrentConsumers(props.getPrefetch().getAnnouncementConcurrency());
        return factory;
    }

    /** 精简 LLM 网关：单渠道 OpenAI 兼容调用；失败抛 LlmGatewayException（瞬时语义）。 */
    public static class LlmGateway {

        private final RestClient client;
        private final LlmGatewayProperties props;

        public LlmGateway(RestClient client, LlmGatewayProperties props) {
            this.client = client;
            this.props = props;
        }

        /**
         * 对话补全调用。
         * @throws LlmGatewayException HTTP/超时/非 2xx/响应不可解析（全部按瞬时语义处理）
         */
        public String chat(String systemPrompt, String userMessage) {
            int maxAttempts = Math.max(1, props.getMaxAttempts());
            LlmGatewayException last = null;
            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                try {
                    return callOnce(systemPrompt, userMessage);
                } catch (Exception e) {
                    last = e instanceof LlmGatewayException le ? le : new LlmGatewayException(e.getMessage(), e);
                    log.warn("LLM 调用失败 attempt={}/{} err={}", attempt, maxAttempts, last.getMessage());
                    if (attempt < maxAttempts) {
                        try {
                            Thread.sleep(500L * attempt);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            throw new LlmGatewayException("interrupted", ie);
                        }
                    }
                }
            }
            throw last;
        }

        /** data 侧无降级模板：恒 false（保留方法与主服务 DistillService 调用形状对齐） */
        public boolean isDegradedResponse(String result) {
            return false;
        }

        private String callOnce(String systemPrompt, String userMessage) {
            Map<String, Object> body = Map.of(
                    "model", props.getModel(),
                    "messages", List.of(
                            Map.of("role", "system", "content", systemPrompt),
                            Map.of("role", "user", "content", userMessage)));
            Map<?, ?> resp = client.post()
                    .uri("/chat/completions")
                    .body(body)
                    .retrieve()
                    .body(Map.class);
            if (resp == null) {
                throw new LlmGatewayException("empty response body", null);
            }
            try {
                List<?> choices = (List<?>) resp.get("choices");
                Map<?, ?> first = (Map<?, ?>) choices.get(0);
                Map<?, ?> message = (Map<?, ?>) first.get("message");
                String content = (String) message.get("content");
                if (!StringUtils.hasText(content)) {
                    throw new LlmGatewayException("empty message content", null);
                }
                return content;
            } catch (LlmGatewayException e) {
                throw e;
            } catch (Exception e) {
                throw new LlmGatewayException("unexpected response shape: " + e.getMessage(), e);
            }
        }
    }

    /** LLM 网关异常（瞬时语义：交主服务 fail_count 计次，达限 FAILED） */
    public static class LlmGatewayException extends RuntimeException {
        public LlmGatewayException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
