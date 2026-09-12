package com.zzh.stock_calculator.crawler;

import com.zzh.stockcalc.contract.message.EmbeddingComputeResult;

/**
 * 公告嵌入任务发布/结果落账端口（设计文档 §4.3 D7 两段式，§8 阶段 4 任务 3）：
 * 公告的「摘要向量化任务下发」与「result.embedding.done(kind=announcement) 落账」
 * 归 announcement 域所有（状态机/实体在域内），crawler 侧（EmbeddingResultService 消费
 * 分流、未来跨域触发）只经本端口调用——端口倒置避免 crawler → announcement 成环
 * （AnnouncementIngestApi 同款模式）。
 * <p>额度记账复用共享 EmbeddingQuotaGuard 单例（D8：严禁第二份独立计数）；
 * 任务发布复用 TaskDispatchApi。</p>
 */
public interface AnnouncementEmbeddingApi {

    /**
     * 二段向量化任务下发（task.embedding.compute，kind=announcement，text=摘要）。
     * 实现内完成：功能开关门控 → 摘要非空校验 → DONE 判重（force 绕过，供对账器
     * 补存量缺向量行）→ 额度扣减（发布端记账 D8）→ 发布。
     *
     * @param force true=绕过 DONE 判重（对账器对 DONE 缺向量行强制补发）
     * @return true=已下发；false=未下发（门控/摘要缺失/已嵌入/额度拒绝，PENDING 留对账）
     */
    boolean dispatchEmbeddingTask(Long announcementId, boolean force);

    /**
     * result.embedding.done（kind=announcement，维度校验已由调用方前置）落账：
     * 确定性 UUID 向量 upsert + 状态行 DONE 同事务成对写（cls S3 语义 1:1）；
     * 指纹判重（向量行 content=当前摘要 且 kind=announcement）→ 重复/晚到结果跳过。
     *
     * @return true=已落账；false=业务性跳过（消费端可安全 ack）
     */
    boolean applyEmbeddingResult(EmbeddingComputeResult result);
}
