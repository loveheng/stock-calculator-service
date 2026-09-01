package com.zzh.stock_calculator.llm.config;

import lombok.Data;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * LLM 多渠道识别参数（llm 前缀）。
 * 渠道优先级固定为 gemini -> groq -> fallback，由各策略类的 @Order 决定；
 * enabled=false 或缺少 Key 的渠道会被 LlmChainRouter 的健康检查跳过。
 */
@Data
@ConfigurationProperties(prefix = "llm")
public class LlmProperties {

    /**
     * 链路内单渠道最大尝试次数。默认 1：Gemini/Groq 免费层的 429 属 RPM/TPM 窗口限流，
     * 短退避重试大概率仍失败且占窗口，直接流转下一渠道性价比更高。
     */
    private int maxAttempts = 1;

    /** 可重试失败后的退避间隔（maxAttempts > 1 时生效） */
    private Duration retryBackoff = Duration.ofMillis(300);

    private final Provider gemini = new Provider();

    private final Provider groq = new Provider();

    private final Fallback fallback = new Fallback();

    /** OpenAI 兼容渠道通用参数（Gemini / Groq 共用一套字段） */
    @Data
    public static class Provider {

        /** 渠道总开关 */
        private boolean enabled = true;

        /** OpenAI 兼容 baseUrl（不含 /chat/completions），如 https://api.groq.com/openai/v1 */
        private String baseUrl = "";

        /** Bearer 鉴权 Key */
        @ToString.Exclude
        private String apiKey = "";

        /** 模型名：免费层模型会轮换下线（尤其 Groq），必须可配置勿硬编码承诺 */
        private String model = "";

        /** 连接超时（java.net.http.HttpClient） */
        private Duration connectTimeout = Duration.ofSeconds(5);

        /** 读取超时 */
        private Duration readTimeout = Duration.ofSeconds(20);
    }

    @Data
    public static class Fallback {

        /** 兜底总开关：false 时全链失败直接抛 BusinessException(503) */
        private boolean enabled = true;

        /** 降级哑响应模板：不调用任何模型，明确告知调用方结果未经 AI 处理 */
        private String response = "[降级响应] AI 渠道暂不可用，本次结果未经模型处理，请稍后重试。";
    }
}
