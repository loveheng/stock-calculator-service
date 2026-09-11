package com.zzh.stockcalc.contract.message;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * result.embedding.done 的 payload（设计文档 §4.3）：worker → 主服务的向量化计算结果。
 * 主服务按 kind + refId 确定性 UUID upsert 向量记录，重复消费无副作用。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EmbeddingComputeResult {

    /** 待算对象类型：EmbeddingComputeTask.KIND_* */
    private String kind;

    /** 业务主键：cls_article.id 或 announcement.id */
    private Long refId;

    /** 模型标识（如 bge-m3），主服务入库记录 */
    private String model;

    /** 向量维度 */
    private Integer dims;

    /** 向量本体（1024 维 JSON ≈ 8KB，MQ 舒适区内） */
    private List<Float> vector;

    /** 本次调用消耗的 tokens（CF 额度记账留主服务，D8：worker 回报、发布端扣减） */
    private Long tokensUsed;
}
