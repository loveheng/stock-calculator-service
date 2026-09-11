package com.zzh.stockcalc.contract.message;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 通用 webhook 摄取文章载荷（设计文档 §8 阶段 5 / 开放问题 1 定案）：
 * data 服务 ingest endpoint 经 HMAC-SHA256 校验 + parser 插件标准化后发布
 * result.article.ingested，主服务复用 CLS 电报入库链（saveArticleWithRelations 幂等 +
 * ArticleSavedEvent 向量化链）。
 * <p>articleId 由数据侧按 IngestArticleIds.of(source, externalId) 确定性生成，
 * 重复推送天然幂等；publishedAt 为 epoch 秒（对齐 ClsArticleDto.ctime 口径），可空。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ArticleIngestedPayload {

    /** data 侧确定性 id（IngestArticleIds 规则），主服务入库幂等锚点 */
    private Long articleId;

    /** 数据源标识（= ingest 路径 {source}，parser 插件注册键） */
    private String source;

    /** 源系统原始 id（幂等规则输入 + 审计） */
    private String externalId;

    private String title;

    private String brief;

    private String content;

    private String author;

    /** epoch 秒（可空；对齐 cls ctime 口径） */
    private Long publishedAt;
}
