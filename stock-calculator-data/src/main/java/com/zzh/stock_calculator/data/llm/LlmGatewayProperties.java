package com.zzh.stock_calculator.data.llm;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 精简 LLM 网关配置（设计文档 §4.4 阶段 4 任务 4）：data 侧不随迁 main 的多渠道
 * 责任链（gemini/groq/fallback），单渠道 OpenAI 兼容 chat-completions 端点即够——
 * worker 无降级语义（全链失败=任务失败回报 failed，交主服务 fail_count 计次）。
 */
@Data
@ConfigurationProperties(prefix = "datasvc.llm")
public class LlmGatewayProperties {

    /** OpenAI 兼容端点 base-url（gemini: https://generativelanguage.googleapis.com/v1beta/openai；groq: https://api.groq.com/openai/v1） */
    private String baseUrl;

    /** API Key */
    private String apiKey;

    /** 蒸馏模型名 */
    private String model;

    /** 单渠道重试次数（429/5xx 短退避，默认 1 = 不重试，交主服务 fail_count 计次） */
    private int maxAttempts = 1;

    /** 请求超时 */
    private java.time.Duration readTimeout = java.time.Duration.ofSeconds(60);
}
