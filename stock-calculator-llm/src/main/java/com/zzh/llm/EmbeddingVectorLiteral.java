package com.zzh.llm;

/**
 * pgvector 字面量工具：float[] → "[a,b,...]"（写入/检索共用；配合 SQL 显式 CAST AS vector）。
 * 自 mcp KbEmbeddingClient / orchestration IntentEmbeddingClient 的同款 static 方法收编。
 */
public final class EmbeddingVectorLiteral {

    private EmbeddingVectorLiteral() {
    }

    public static String of(float[] vector) {
        StringBuilder sb = new StringBuilder(vector.length * 10).append('[');
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(vector[i]);
        }
        return sb.append(']').toString();
    }
}
