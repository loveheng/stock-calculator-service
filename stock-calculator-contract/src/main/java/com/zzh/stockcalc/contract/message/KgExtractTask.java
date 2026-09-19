package com.zzh.stockcalc.contract.message;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * task.kg.extract 的 payload（docs/ai-pipeline/cls-news-kg.md §4）：
 * main → data 的《新闻联播》要闻知识图谱抽取任务。正文直入 payload（汇编稿仅数百字），
 * data 无 DB 不回源；contentHash 供 main 摄取侧判重与源站改稿重抽对照。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KgExtractTask {

    /** cls_article 主键（幂等锚点） */
    private Long articleId;

    /** 正文指纹（SHA-256，摄取判重 + 改稿重抽对照） */
    private String contentHash;

    /** 标题（日志/排查可读性；标题日期与联播播出日可能差一天，不作事件日期依据） */
    private String title;

    /** 文章发布时间（epoch 秒，相对时间归一化基准） */
    private Long ctime;

    /** 电报正文（600~800 字汇编稿） */
    private String content;
}
