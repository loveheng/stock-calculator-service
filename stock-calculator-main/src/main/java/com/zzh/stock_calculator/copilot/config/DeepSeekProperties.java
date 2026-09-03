package com.zzh.stock_calculator.copilot.config;

import lombok.Data;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * DeepSeek 渠道连接参数（copilot.llm.deepseek.* 前缀）。
 * copilot 问答专用付费渠道；OpenAI 兼容端点，
 * 与 llm 域免费渠道（llm.gemini/groq，引流功能专用）完全独立，互不混用。
 */
@Data
@ConfigurationProperties(prefix = "copilot.llm.deepseek")
public class DeepSeekProperties {

    /** 渠道总开关 */
    private boolean enabled = true;

    /** DeepSeek OpenAI 兼容端点地址 */
    private String baseUrl;

    /** API Key（日志脱敏） */
    @ToString.Exclude
    private String apiKey;

    /** 模型名（如 deepseek-chat、deepseek-reasoner） */
    private String model;

    /** 单次请求超时 */
    private java.time.Duration readTimeout = java.time.Duration.ofSeconds(20);
}
