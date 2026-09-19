package com.zzh.stock_calculator.crawler;

import com.zzh.stockcalc.contract.message.KgExtractDonePayload;
import com.zzh.stockcalc.contract.message.KgExtractFailedPayload;

/**
 * result.kg.* 摄取端口（docs/ai-pipeline/cls-news-kg.md §2/§6）：crawler.mq 消费端
 * （ClsArticleMqConsumer）按此端口分发 KG 抽取结果，实现由 kg 域提供
 * （KgResultService）。端口倒置避免 crawler ↔ kg 模块环（kg 已依赖 crawler 基包，
 * Modulith 禁环），与 AnnouncementIngestApi 同款宿主规则。
 */
public interface KgIngestApi {

    /**
     * result.kg.done 入库（D7 两段式）：kg_evidence 证据行 upsert（contentHash 判重，
     * 一篇文章一版，改稿覆盖）→ 任务状态 DONE → 图谱融合（独立事务，失败不回退任务状态，
     * 证据可重放）。
     *
     * @return true=已处理（含重复投递幂等）；false=载荷非法/未知任务行（ack 丢弃）
     */
    boolean ingestDone(KgExtractDonePayload payload);

    /**
     * result.kg.failed 入库（D10 错误三分类）：fail_count 计次，PERMANENT/达
     * maxFailAttempts 落 FAILED 终态；RATE_LIMITED 额外触发发布端熔断窗口。
     *
     * @return true=已处理；false=载荷非法/未知任务行/非 PENDING 行（ack 丢弃）
     */
    boolean ingestFailed(KgExtractFailedPayload payload);
}
