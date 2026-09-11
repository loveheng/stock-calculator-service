package com.zzh.stock_calculator.announcement.service;

import com.zzh.stock_calculator.announcement.config.AnnouncementProperties;
import com.zzh.stock_calculator.announcement.entity.Announcement;
import com.zzh.stock_calculator.announcement.entity.AnnouncementContent;
import com.zzh.stock_calculator.announcement.entity.AnnouncementFailReason;
import com.zzh.stock_calculator.announcement.entity.AnnouncementStatus;
import com.zzh.stock_calculator.announcement.entity.AnnouncementSubscription;
import com.zzh.stock_calculator.announcement.mq.AnnouncementProcessPublisher;
import com.zzh.stock_calculator.announcement.repository.AnnouncementContentRepository;
import com.zzh.stock_calculator.announcement.repository.AnnouncementRepository;
import com.zzh.stock_calculator.announcement.repository.AnnouncementSubscriptionRepository;
import com.zzh.stock_calculator.crawler.AnnouncementEmbeddingApi;
import com.zzh.stock_calculator.crawler.AnnouncementIngestApi;
import com.zzh.stockcalc.contract.message.AnnouncementCollectedPayload;
import com.zzh.stockcalc.contract.message.AnnouncementDonePayload;
import com.zzh.stockcalc.contract.message.AnnouncementFailedPayload;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * result.announcement.* 摄取服务（设计文档 §4.3/§8 阶段 4 任务 2/3）：
 * 采集/处理结果入端口（crawler.AnnouncementIngestApi 的 announcement 域实现）。
 * collected：announcementId 幂等 + 超体积终态 + orgId 回填（任务 2）；
 * done：content 溯源行 1:1 upsert（正文不落库，D5）+ summary 落账 → 二段向量化
 * 任务下发（任务 3，D7 两段式）；failed：failCount 计次/终态判定 + RATE_LIMITED
 * 发布端熔断（§4.5）。状态机归主服务（D7），worker 只回报错误分类。
 * 无 @Transactional：content 先行 → summary 次之的分开 save 与进程内同款崩溃安全序。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AnnouncementResultService implements AnnouncementIngestApi {

    private final AnnouncementRepository announcementRepository;
    private final AnnouncementSubscriptionRepository subscriptionRepository;
    private final AnnouncementContentRepository contentRepository;
    private final AnnouncementProperties properties;
    private final ObjectMapper objectMapper;
    /** 二段向量化端口（MQ 面实现；ObjectProvider 防实现缺失阻启动） */
    private final ObjectProvider<AnnouncementEmbeddingApi> embeddingApiProvider;
    /** 发布端熔断（RATE_LIMITED 冷却窗口；MQ 关闭时不装配） */
    private final ObjectProvider<AnnouncementProcessPublisher> processPublisherProvider;

    @Override
    public boolean ingestCollected(AnnouncementCollectedPayload payload) {
        if (payload == null || isBlank(payload.getAnnouncementId()) || isBlank(payload.getAdjunctUrl())) {
            log.warn("announcement collected payload unusable, dropped, announcementId={}",
                    payload == null ? null : payload.getAnnouncementId());
            return false;
        }
        if (announcementRepository.existsByAnnouncementId(payload.getAnnouncementId())) {
            log.debug("announcement dedup skip, announcementId={}", payload.getAnnouncementId());
            backfillOrgId(payload.getSecCode(), payload.getOrgId());
            return true;
        }
        Announcement.AnnouncementBuilder builder = Announcement.builder()
                .announcementId(payload.getAnnouncementId())
                .title(payload.getTitle() == null ? "" : payload.getTitle())
                .adjunctUrl(payload.getAdjunctUrl())
                .seDate(parseSeDate(payload.getSeDate()))
                .secCode(payload.getSecCode())
                .secName(payload.getSecName());
        long maxBytes = (long) properties.getPdf().getMaxSizeMb() * 1024 * 1024;
        long pdfBytes = (payload.getAdjunctSize() == null ? 0L : payload.getAdjunctSize()) * 1024L;
        if (pdfBytes > maxBytes) {
            // 超体积毒丸直接终态，拒绝进入处理队列（§5 内存炸弹防线前置）
            announcementRepository.save(builder
                    .status(AnnouncementStatus.FAILED)
                    .statusReason(AnnouncementFailReason.DOWNLOAD_FAIL)
                    .build());
            log.info("公告超体积上限拒绝 announcementId={} sizeKB={}",
                    payload.getAnnouncementId(), payload.getAdjunctSize());
            backfillOrgId(payload.getSecCode(), payload.getOrgId());
            return true;
        }
        announcementRepository.save(builder.build());
        log.info("announcement collected ingested as PENDING, announcementId={}", payload.getAnnouncementId());
        backfillOrgId(payload.getSecCode(), payload.getOrgId());
        return true;
    }

    @Override
    public boolean ingestDone(AnnouncementDonePayload payload) {
        if (payload == null || isBlank(payload.getAnnouncementId()) || isBlank(payload.getSummary())) {
            log.warn("announcement done payload unusable, dropped, announcementId={}",
                    payload == null ? null : payload.getAnnouncementId());
            return false;
        }
        Announcement announcement = announcementRepository
                .findByAnnouncementId(payload.getAnnouncementId()).orElse(null);
        if (announcement == null) {
            // 发布端只对存在行下发任务；此为投递窗口内被删的兑底，丢弃
            log.warn("announcement done for unknown row, dropped, announcementId={}",
                    payload.getAnnouncementId());
            return false;
        }
        if (announcement.getStatus() == AnnouncementStatus.FAILED) {
            // 终态语义（D7）：FAILED 后的迟到成功回报不回退状态
            log.warn("announcement done for terminal FAILED row, ignored, announcementId={}",
                    payload.getAnnouncementId());
            return false;
        }
        // 落库顺序与进程内一致（崩溃安全）：content（溯源）先行 → summary（断点续传锚点）次之
        upsertContent(announcement.getId(), payload);
        announcement.setSummary(payload.getSummary());
        announcementRepository.save(announcement);
        log.info("announcement done ingested, announcementId={}, summaryLen={}",
                payload.getAnnouncementId(), payload.getSummary().length());
        // D7 两段式：向量经 task.embedding.compute 二段下发（DONE 判重/额度在端口内），
        // 失败/额度拒绝不重试——PENDING+summary 行由发布端扫描与回填对账兜底（D6）
        AnnouncementEmbeddingApi embeddingApi = embeddingApiProvider.getIfAvailable();
        if (embeddingApi == null) {
            log.warn("announcement embedding api absent, dispatch deferred to reconciler, id={}",
                    announcement.getId());
            return true;
        }
        embeddingApi.dispatchEmbeddingTask(announcement.getId(), false);
        return true;
    }

    @Override
    public boolean ingestFailed(AnnouncementFailedPayload payload) {
        if (payload == null || isBlank(payload.getAnnouncementId())) {
            log.warn("announcement failed payload unusable, dropped");
            return false;
        }
        Announcement announcement = announcementRepository
                .findByAnnouncementId(payload.getAnnouncementId()).orElse(null);
        if (announcement == null) {
            log.warn("announcement failed for unknown row, dropped, announcementId={}",
                    payload.getAnnouncementId());
            return false;
        }
        if (announcement.getStatus() != AnnouncementStatus.PENDING) {
            // DONE 后的过期失败回报 / FAILED 重复回报：终态语义不回退、不重复计次（D7）
            log.info("announcement failed for non-PENDING row, ignored, announcementId={}, status={}",
                    payload.getAnnouncementId(), announcement.getStatus());
            return false;
        }
        AnnouncementFailReason reason = parseReason(payload.getFailReason());
        int failCount = (announcement.getFailCount() == null ? 0 : announcement.getFailCount()) + 1;
        announcement.setFailCount(failCount);
        boolean permanent = AnnouncementFailedPayload.ERROR_KIND_PERMANENT.equals(payload.getErrorKind());
        if (permanent || failCount >= properties.getProcess().getMaxFailAttempts()) {
            announcement.setStatus(AnnouncementStatus.FAILED);
            announcement.setStatusReason(reason);
            log.warn("公告落终态（worker 回报）announcementId={} reason={} kind={} err={}",
                    payload.getAnnouncementId(), reason, payload.getErrorKind(), payload.getMessage());
        } else {
            // TRANSIENT/RATE_LIMITED：留 PENDING，由发布端 PENDING 扫描重发（D6 对账）
            log.info("公告瞬时失败计次（worker 回报）announcementId={} failCount={} kind={} err={}",
                    payload.getAnnouncementId(), failCount, payload.getErrorKind(), payload.getMessage());
        }
        announcementRepository.save(announcement);
        if (AnnouncementFailedPayload.ERROR_KIND_RATE_LIMITED.equals(payload.getErrorKind())) {
            // §4.5：原批级熔断改发布端——暂停公告任务发布窗口（冷却结束自动恢复）
            AnnouncementProcessPublisher publisher = processPublisherProvider.getIfAvailable();
            if (publisher != null) {
                publisher.markRateLimited();
            }
        }
        return true;
    }

    /** content 溯源行 1:1 upsert：结构树/切片选择 JSONB + 重放三件套；正文不落库（D5） */
    private void upsertContent(Long announcementId, AnnouncementDonePayload payload) {
        AnnouncementContent content = contentRepository.findById(announcementId)
                .orElseGet(() -> AnnouncementContent.builder().announcementId(announcementId).build());
        content.setExtractorVersion(payload.getExtractorVersion());
        content.setCharCount(payload.getCharCount());
        content.setPageCount(payload.getPageCount());
        if (payload.getStructure() != null) {
            content.setStructureJson(objectMapper.writeValueAsString(payload.getStructure()));
        }
        if (payload.getSelection() != null) {
            content.setSelectionJson(objectMapper.writeValueAsString(payload.getSelection()));
        }
        contentRepository.save(content);
    }

    /** worker 按名上报（契约不引主服务枚举）；未知/缺失兑底 PARSE_FAIL（日志留痕） */
    private AnnouncementFailReason parseReason(String name) {
        if (isBlank(name)) {
            return AnnouncementFailReason.PARSE_FAIL;
        }
        try {
            return AnnouncementFailReason.valueOf(name);
        } catch (IllegalArgumentException e) {
            log.warn("unknown failReason from worker, fallback PARSE_FAIL, value={}", name);
            return AnnouncementFailReason.PARSE_FAIL;
        }
    }

    /**
     * orgId 回填（R3）：collector 侧解析结果回推，仅补空白行，存量不覆盖
     * （与 AnnouncementCollectService.resolveOrgId 同语义）。
     */
    private void backfillOrgId(String stockId, String orgId) {
        if (isBlank(stockId) || isBlank(orgId)) {
            return;
        }
        List<AnnouncementSubscription> rows = subscriptionRepository.findByStockId(stockId);
        for (AnnouncementSubscription row : rows) {
            if (row.getOrgId() == null || row.getOrgId().isBlank()) {
                row.setOrgId(orgId);
                subscriptionRepository.save(row);
            }
        }
    }

    /** ISO yyyy-MM-dd 解析；非法/缺失容错为 null（状态机按无公告日处理） */
    private LocalDate parseSeDate(String seDate) {
        if (isBlank(seDate)) {
            return null;
        }
        try {
            return LocalDate.parse(seDate);
        } catch (DateTimeParseException e) {
            log.warn("announcement seDate unparsable, stored null, value={}", seDate);
            return null;
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
