package com.zzh.stockcalc.contract.message;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * result.kg.done 的 payload（docs/ai-pipeline/cls-news-kg.md §4）：
 * worker → main 的抽取成功回报。main 侧两段落库：kg_evidence 证据行
 * （contentHash 判重）先行独立事务，随后 KgFuseService 融合进图谱；
 * 融合失败不回退任务状态（证据可重放）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KgExtractDonePayload {

    /** cls_article 主键（幂等锚点） */
    private Long articleId;

    /** 抽取时正文指纹（与任务下发一致；源站改稿后 hash 变化触发重抽） */
    private String contentHash;

    /** 文章发布时间（epoch 秒，任务回传；融合侧实体时间窗与事件兜底基准） */
    private Long ctime;

    /** 抽取模型标识（落证据表 model 列） */
    private String model;

    /** 结构化抽取结果 */
    private KgExtraction extraction;
}
