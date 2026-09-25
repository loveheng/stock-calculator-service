package com.zzh.llm;

import java.util.List;

/**
 * embedding 客户端抽象（供应商无关）：调用侧只依赖本接口，
 * 具体实现由 {@link LlmRegistry#embedClient} 按 EmbedSpec.provider 装配。
 */
public interface EmbeddingClient {

    /** 批量向量化（自动分批），返回与输入顺序一致的向量列表 */
    List<float[]> embed(List<String> texts);

    /** 单文本向量化，失败抛 IllegalStateException 由调用方决定降级 */
    float[] embedOne(String text);

    String getModel();

    int getDimensions();
}
