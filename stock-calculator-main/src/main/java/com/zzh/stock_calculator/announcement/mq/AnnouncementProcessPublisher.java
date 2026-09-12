package com.zzh.stock_calculator.announcement.mq;

import com.zzh.stock_calculator.announcement.config.AnnouncementProperties;
import com.zzh.stock_calculator.announcement.entity.Announcement;
import com.zzh.stock_calculator.announcement.entity.AnnouncementStatus;
import com.zzh.stock_calculator.announcement.repository.AnnouncementRepository;
import com.zzh.stock_calculator.crawler.AnnouncementEmbeddingApi;
import com.zzh.stock_calculator.crawler.TaskDispatchApi;
import com.zzh.stockcalc.contract.MessageType;
import com.zzh.stockcalc.contract.message.AnnouncementProcessTask;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 公告处理任务发布端（设计文档 §4.3/§4.4/§8 阶段 4 任务 3）：PENDING 扫描每轮重发
 * 未终态的（原 processNextBatch 的「扫描 → 处理」改造为「扫描 → 发布」，处理移交 worker；
 * 计算型任务重跑无害，at-least-once + 幂等，D6）。
 * <p>两级分发：待蒸馏（PENDING 且摘要空，近端优先 50 条/批与进程内同款）发
 * task.announcement.process；已蒸馏未向量化（PENDING 且摘要非空，断点续传态）经
 * AnnouncementEmbeddingApi 直接补发二段任务，避免 worker 全量重处理。</p>
 * <p>发布端熔断（§4.5）：worker 回报 RATE_LIMITED → markRateLimited 暂停发布窗口
 * （原批级 break 熔断的发布端等价物），冷却结束自动恢复。</p>
 * <p>PENDING 扫描发布为公告处理唯一路径（MQ 单路径终态），发布端熔断见 §4.5。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AnnouncementProcessPublisher {

    private final AnnouncementRepository announcementRepository;
    private final TaskDispatchApi taskDispatchApi;
    private final AnnouncementProperties properties;
    private final ObjectProvider<AnnouncementEmbeddingApi> embeddingApiProvider;

    /** 发布端熔断窗口截止时刻（RATE_LIMITED 上报后顺延） */
    private volatile Instant rateLimitedUntil = Instant.EPOCH;

    /**
     * 扫描 PENDING 并发布任务（AnnouncementProcessTask 调度入口）。
     * @return 已发布任务数（process + embedding 合计，供调度日志）
     */
    public int publishPendingBatch() {
        if (!properties.getProcess().isEnabled()) {
            return 0;
        }
        Instant until = rateLimitedUntil;
        if (Instant.now().isBefore(until)) {
            log.info("CNINFO 限流冷却中（至 {}），本轮公告任务发布跳过", until);
            return 0;
        }
        int processDispatched = dispatchProcessTasks();
        int embeddingDispatched = dispatchEmbeddingTasks();
        log.info("公告任务发布完成 process={} embedding={}（未终态每轮重发，D6 对账）",
                processDispatched, embeddingDispatched);
        return processDispatched + embeddingDispatched;
    }

    /** RATE_LIMITED 上报 → 暂停发布窗口（announcement.process.rate-limit-cooldown-minutes） */
    public void markRateLimited() {
        long minutes = properties.getProcess().getRateLimitCooldownMinutes();
        rateLimitedUntil = Instant.now().plus(Duration.ofMinutes(minutes));
        log.warn("CNINFO 限流上报，公告任务发布暂停 {} 分钟", minutes);
    }

    /** 待蒸馏 PENDING（摘要空）→ task.announcement.process（只传元数据，worker 自下载 PDF） */
    private int dispatchProcessTasks() {
        List<Announcement> batch = announcementRepository
                .findTop50ByStatusAndSummaryIsNullOrderBySeDateDescIdDesc(AnnouncementStatus.PENDING);
        int dispatched = 0;
        for (Announcement announcement : batch) {
            boolean ok = taskDispatchApi.dispatchTask(MessageType.TASK_ANNOUNCEMENT_PROCESS,
                    AnnouncementProcessTask.builder()
                            .announcementId(announcement.getAnnouncementId())
                            .title(announcement.getTitle())
                            .adjunctUrl(announcement.getAdjunctUrl())
                            .secCode(announcement.getSecCode())
                            .build());
            if (ok) {
                dispatched++;
            }
        }
        return dispatched;
    }

    /** 已蒸馏未向量化 PENDING（摘要非空，断点续传态）→ 二段向量化任务补发 */
    private int dispatchEmbeddingTasks() {
        AnnouncementEmbeddingApi embeddingApi = embeddingApiProvider.getIfAvailable();
        if (embeddingApi == null) {
            return 0;
        }
        List<Announcement> pending = announcementRepository
                .findByStatusAndSummaryNotNull(AnnouncementStatus.PENDING);
        int dispatched = 0;
        for (Announcement announcement : pending) {
            if (embeddingApi.dispatchEmbeddingTask(announcement.getId(), false)) {
                dispatched++;
            }
        }
        return dispatched;
    }
}
