package com.zzh.stock_calculator.mcp.kb;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * kb 向量化门面（离线灌书 + 查询侧）：HTTP 逻辑已收编进 stock-calculator-llm 的
 * {@link com.zzh.llm.EmbeddingClient}（provider 可切换 cloudflare | openai，凭据读
 * ai.embeddings.embed），本类只保留 kb 侧语义 API 与 pgvector 字面量工具。
 *
 * <p>维度 1024（kb_chunk.embedding 列 vector(1024)，模型换型须全量重嵌，model 列留档）。</p>
 */
@Slf4j
@Component
public class KbEmbeddingClient {

    private final com.zzh.llm.EmbeddingClient delegate;

    public KbEmbeddingClient(com.zzh.llm.LlmRegistry llmRegistry) {
        // 宽松语义（沿旧）：凭据未配齐不阻启动，调用时报错
        if (llmRegistry.isEmbedReady(com.zzh.llm.LlmTiers.EMBED)) {
            this.delegate = llmRegistry.embedClient(com.zzh.llm.LlmTiers.EMBED);
        } else {
            log.warn("ai.embeddings.embed 凭据未配齐：kb 灌书与向量检索不可用（查询/工具层将降级报错）");
            this.delegate = null;
        }
    }

    /** 批量向量化（自动分批），返回与输入顺序一致的向量列表 */
    public List<float[]> embed(List<String> texts) {
        requireDelegate();
        return delegate.embed(texts);
    }

    /** 当前 tier 配置的 embedding 模型名（kb 记录留档用） */
    public String getModel() {
        requireDelegate();
        return delegate.getModel();
    }

    /** 向量维度（来自 ai.embeddings.embed.dimensions，默认 1024） */
    public int getDimensions() {
        requireDelegate();
        return delegate.getDimensions();
    }

    private void requireDelegate() {
        if (delegate == null) {
            throw new IllegalStateException("embedding 不可用（ai.embeddings.embed 凭据未配齐）");
        }
    }

}
