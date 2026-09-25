package com.zzh.llm;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * LLM tier 全局配置（唯一事实源）。
 * <pre>
 * ai:
 *   tiers:                                      # OpenAI 兼容 chat 端点
 *     openai-max:  { base-url: ..., api-key: ..., model: ..., temperature: 0.0 }
 *     openai-mini: { base-url: ..., api-key: ..., model: ..., max-tokens: 512, max-retries: 0 }
 *   embeddings:                                 # OpenAI 兼容 /embeddings 端点
 *     openai-embed: { base-url: ..., api-key: ..., model: BAAI/bge-m3, dimensions: 1024 }
 * </pre>
 * tier 名常量见 {@link LlmTiers}（调用侧禁止自起别名）；chat 档位语义：max=强推理、
 * chat=常规对话、mini=廉价批量。
 */
@Data
@ConfigurationProperties(prefix = "ai")
public class LlmTierProperties {

    private Map<String, TierSpec> tiers = new LinkedHashMap<>();

    /** embedding tier（ai.embeddings.&lt;name&gt;.*）：OpenAI 兼容 /embeddings 端点 */
    private Map<String, EmbedSpec> embeddings = new LinkedHashMap<>();
}
