package com.zzh.stock_calculator.orchestration.planner;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 意图向量化客户端（agent-orchestration §6.2/§七）：HTTP 逻辑已收编进
 * stock-calculator-llm 的 {@link com.zzh.llm.EmbeddingClient}（provider 可切换
 * cloudflare | openai，凭据读 ai.embeddings.embed），维度与 plan.intent_embedding
 * vector(1024) 对齐。
 */
@Slf4j
@Component
public class IntentEmbeddingClient {

    private final com.zzh.llm.EmbeddingClient delegate;

    public IntentEmbeddingClient(com.zzh.llm.LlmRegistry llmRegistry) {
        // 宽松语义（沿旧）：凭据未配齐不阻启动，调用时报错（复用降级为每次完整规划）
        if (llmRegistry.isEmbedReady(com.zzh.llm.LlmTiers.EMBED)) {
            this.delegate = llmRegistry.embedClient(com.zzh.llm.LlmTiers.EMBED);
        } else {
            log.warn("ai.embeddings.embed 凭据未配齐：plan 向量匹配不可用（复用降级为每次完整规划）");
            this.delegate = null;
        }
    }

    /** 单文本向量化（意图查询侧一次一条），失败抛 IllegalStateException 由调用方决定降级 */
    public float[] embed(String text) {
        if (delegate == null) {
            throw new IllegalStateException("embedding 不可用（ai.embeddings.embed 凭据未配齐）");
        }
        return delegate.embedOne(text);
    }

}
