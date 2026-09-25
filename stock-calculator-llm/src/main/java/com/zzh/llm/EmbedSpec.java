package com.zzh.llm;

import lombok.Data;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

import java.time.Duration;

/**
 * 单个 embedding tier 的规格（ai.embeddings.&lt;name&gt;.*），供应商可切换：
 * <ul>
 *   <li>{@code provider=cloudflare}（默认，现状）：Workers AI 原生 /ai/run 直调 +
 *       Spring AI 路径走 CF OpenAI 垫片（/ai/v1，CfUsageFixingClient 修 usage 缺失）；
 *       必填 account-id / api-token / model；</li>
 *   <li>{@code provider=openai}：任意 OpenAI 兼容 /embeddings 端点（SiliconFlow
 *       BAAI/bge-m3 等）；必填 base-url / api-key / model。</li>
 * </ul>
 * 维度换型须全量重嵌并同步列宽。三键（凭据+model）缺一不可，LlmRegistry 首次取用时校验。
 */
@Data
public class EmbedSpec {

    /** 供应商实现：cloudflare（Workers AI）| openai（OpenAI 兼容 /embeddings） */
    @NestedConfigurationProperty
    private String provider = "cloudflare";

    /** CF 账户 ID（provider=cloudflare 必填） */
    @NestedConfigurationProperty
    private String accountId;

    /** API 凭据（CF Token / OpenAI 兼容 key，日志脱敏） */
    @NestedConfigurationProperty
    private String apiToken;

    /** OpenAI 兼容端点 base-url（provider=openai 必填；cloudflare 时忽略，自动拼接） */
    @NestedConfigurationProperty
    private String baseUrl;

    /** embedding 模型（CF: @cf/baai/bge-m3；SiliconFlow: BAAI/bge-m3） */
    @NestedConfigurationProperty
    private String model = "@cf/baai/bge-m3";

    /** 向量维度（bge-m3 固定 1024；换模型必须全量重嵌并同步列宽） */
    @NestedConfigurationProperty
    private int dimensions = 1024;

    /** 单次 HTTP 请求超时；null = 默认 30s */
    @NestedConfigurationProperty
    private Duration timeout;

    /** Spring AI 客户端内建重试次数（null/0 = 不重试，429 原样抛出交上层熔断分类） */
    @NestedConfigurationProperty
    private Integer maxRetries;

    /** 生效超时（含默认值） */
    public Duration effectiveTimeout() {
        return timeout == null ? Duration.ofSeconds(30) : timeout;
    }
}
