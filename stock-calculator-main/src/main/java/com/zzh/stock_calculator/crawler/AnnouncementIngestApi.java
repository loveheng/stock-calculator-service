package com.zzh.stock_calculator.crawler;

import com.zzh.stockcalc.contract.message.AnnouncementCollectedPayload;
import com.zzh.stockcalc.contract.message.AnnouncementDonePayload;
import com.zzh.stockcalc.contract.message.AnnouncementFailedPayload;

/**
 * result.announcement.* 摄取端口（设计文档 §4.3/§8 阶段 4 任务 2/3）：crawler.mq 消费端
 * （ClsArticleMqConsumer）按此端口分发公告采集/处理结果，实现由 announcement 域提供
 * （AnnouncementResultService）。端口倒置避免 crawler → announcement 模块环
 * （announcement 已依赖 crawler 基包，Modulith 禁环）。
 */
public interface AnnouncementIngestApi {

    /**
     * result.announcement.collected 入库：announcementId 幂等（存在即去重跳过）→
     * 超体积前置拒绝（FAILED/DOWNLOAD_FAIL）→ PENDING 落库 → orgId 回填订阅行（R3 对账）。
     * @return true=已处理（含去重跳过，消费端 ack）；false=载荷非法（ack 丢弃）
     */
    boolean ingestCollected(AnnouncementCollectedPayload payload);

    /**
     * result.announcement.done 入库（阶段 4 任务 3，D7 两段式）：content 溯源行 1:1 upsert
     * （正文不落库，D5）+ summary 落账（断点续传锚点）→ 二段向量化任务下发（端口内）。
     * @return true=已处理（含重复投递幂等）；false=载荷非法/未知公告/终态行（ack 丢弃）
     */
    boolean ingestDone(AnnouncementDonePayload payload);

    /**
     * result.announcement.failed 入库（D7 状态机留主服务）：failCount 计次，
     * PERMANENT/达 maxFailAttempts 落 FAILED 终态；RATE_LIMITED 额外触发发布端熔断窗口（§4.5）。
     * @return true=已处理；false=载荷非法/未知公告/非 PENDING 行（ack 丢弃）
     */
    boolean ingestFailed(AnnouncementFailedPayload payload);
}
