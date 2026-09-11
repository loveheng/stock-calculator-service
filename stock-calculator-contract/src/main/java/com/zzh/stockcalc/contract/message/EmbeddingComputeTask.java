package com.zzh.stockcalc.contract.message;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * task.embedding.compute 的 payload（设计文档 §4.3）：主服务 → worker 的向量化计算任务。
 * 只传元数据与待算文本，计算型任务重跑无害（幂等锚点 = kind + refId 确定性向量主键）。
 * announcement 的 text 仅摘要（正文抽取在 worker 内完成，不跨 MQ 传大文本）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EmbeddingComputeTask {

    public static final String KIND_CLS_ARTICLE = "cls_article";
    public static final String KIND_ANNOUNCEMENT = "announcement";

    /** 待算对象类型：EmbeddingComputeTask.KIND_* */
    private String kind;

    /** 业务主键：cls_article.id 或 announcement.id */
    private Long refId;

    /** 待向量化文本；announcement 场景传摘要 */
    private String text;
}
