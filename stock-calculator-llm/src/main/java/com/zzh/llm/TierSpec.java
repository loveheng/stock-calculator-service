package com.zzh.llm;

import lombok.Data;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

import java.time.Duration;

/**
 * 单个 chat tier 的连接与采样规格（ai.tiers.&lt;name&gt;.*）。
 * <p>三键（base-url/api-key/model）缺一不可，LlmRegistry 首次取用时 fail-fast 校验。</p>
 */
@Data
public class TierSpec {

    @NestedConfigurationProperty
    private String baseUrl;

    @NestedConfigurationProperty
    private String apiKey;

    @NestedConfigurationProperty
    private String model;

    /** 采样温度；null = 不设置（走 API 默认） */
    @NestedConfigurationProperty
    private Double temperature;

    /** 显式 maxTokens 防 JSON 腰斩；null = 不设置 */
    @NestedConfigurationProperty
    private Integer maxTokens;

    /** 整次请求上限（映射 OkHttp callTimeout，含流式全程）；null = SDK 默认 */
    @NestedConfigurationProperty
    private Duration timeout;

    /** openai-java 内建重试次数；批量 worker 建议 0（异常原样抛出交上层分流） */
    @NestedConfigurationProperty
    private Integer maxRetries;
}
