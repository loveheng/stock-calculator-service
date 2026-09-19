package com.zzh.stock_calculator.kg.service;

import com.zzh.stock_calculator.crawler.KgIngestApi;
import com.zzh.stock_calculator.kg.config.KgProperties;
import com.zzh.stock_calculator.kg.entity.ClsArticleKg;
import com.zzh.stock_calculator.kg.entity.KgEvidence;
import com.zzh.stock_calculator.kg.entity.KgTaskStatus;
import com.zzh.stock_calculator.kg.mq.KgExtractPublisher;
import com.zzh.stock_calculator.kg.repository.ClsArticleKgRepository;
import com.zzh.stock_calculator.kg.repository.KgEvidenceRepository;
import com.zzh.stockcalc.contract.message.KgExtractDonePayload;
import com.zzh.stockcalc.contract.message.KgExtractFailedPayload;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;

/**
 * result.kg.* 摄取服务（docs/ai-pipeline/cls-news-kg.md §6/§9，crawler.KgIngestApi 的
 * kg 域实现）：done → 证据行 upsert（D7 断点安全序：证据先行）→ 任务 DONE → 图谱融合
 * （独立事务，失败不回退任务状态、证据可重放）；failed → fail_count 计次/终态判定 +
 * RATE_LIMITED 发布端熔断。无类级 @Transactional：证据与状态分开 save 的崩溃安全序
 * （中途崩溃留 PENDING 行，下一轮对账重发自愈）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KgResultService implements KgIngestApi {

    private final ClsArticleKgRepository stateRepository;
    private final KgEvidenceRepository evidenceRepository;
    private final KgProperties properties;
    private final ObjectMapper objectMapper;
    /** 融合服务（独立事务边界；失败由本服务捕获留痕，任务不回退） */
    private final KgFuseService fuseService;
    /** 发布端熔断（RATE_LIMITED 冷却窗口；ObjectProvider 防实现缺失阻启动） */
    private final ObjectProvider<KgExtractPublisher> publisherProvider;

    @Override
    public boolean ingestDone(KgExtractDonePayload payload) {
        if (payload == null || payload.getArticleId() == null || payload.getExtraction() == null) {
            log.warn("kg done payload unusable, dropped, articleId={}",
                    payload == null ? null : payload.getArticleId());
            return false;
        }
        ClsArticleKg state = stateRepository.findById(payload.getArticleId()).orElse(null);
        if (state == null) {
            // 发布端只对状态行存在的文章下发；此为投递窗口内异常的兜底，丢弃
            log.warn("kg done for unknown row, dropped, articleId={}", payload.getArticleId());
            return false;
        }
        if (state.getStatus() == KgTaskStatus.FAILED) {
            // 终态语义（D7）：FAILED 后的迟到成功回报不回退状态
            log.warn("kg done for terminal FAILED row, ignored, articleId={}", payload.getArticleId());
            return false;
        }
        String hash = payload.getContentHash() == null ? "" : payload.getContentHash();
        if (state.getStatus() == KgTaskStatus.DONE && hash.equals(state.getContentHash())) {
            // 重复投递幂等：融合内部 mention_count 累加不可重放，重复 done 必须整单跳过
            log.debug("kg done dedup skip, articleId={}", payload.getArticleId());
            return true;
        }
        upsertEvidence(payload, hash);
        state.setStatus(KgTaskStatus.DONE);
        state.setContentHash(hash);
        state.setStatusReason(null);
        state.setExtractedAt(OffsetDateTime.now());
        stateRepository.save(state);
        log.info("kg done ingested, articleId={}, entities={}, relations={}, events={}",
                payload.getArticleId(),
                sizeOf(payload.getExtraction().getEntities()),
                sizeOf(payload.getExtraction().getRelations()),
                sizeOf(payload.getExtraction().getEvents()));
        try {
            fuseService.fuse(payload.getArticleId(), payload.getExtraction(), payload.getCtime());
        } catch (Exception e) {
            // D7：融合失败不回退任务状态——证据已落，可从证据重放融合（人工/二期回填入口）
            log.error("kg fuse failed, evidence kept for replay, articleId={}", payload.getArticleId(), e);
            state.setStatusReason(truncate("FUSE_FAILED: " + e.getMessage()));
            stateRepository.save(state);
        }
        return true;
    }

    @Override
    public boolean ingestFailed(KgExtractFailedPayload payload) {
        if (payload == null || payload.getArticleId() == null) {
            log.warn("kg failed payload unusable, dropped");
            return false;
        }
        ClsArticleKg state = stateRepository.findById(payload.getArticleId()).orElse(null);
        if (state == null) {
            log.warn("kg failed for unknown row, dropped, articleId={}", payload.getArticleId());
            return false;
        }
        if (state.getStatus() != KgTaskStatus.PENDING) {
            // DONE 后的过期失败回报 / FAILED 重复回报：终态语义不回退、不重复计次（D7）
            log.info("kg failed for non-PENDING row, ignored, articleId={}, status={}",
                    payload.getArticleId(), state.getStatus());
            return false;
        }
        int failCount = (state.getFailCount() == null ? 0 : state.getFailCount()) + 1;
        state.setFailCount(failCount);
        boolean permanent = KgExtractFailedPayload.ERROR_KIND_PERMANENT.equals(payload.getErrorKind());
        if (permanent || failCount >= properties.getProcess().getMaxFailAttempts()) {
            state.setStatus(KgTaskStatus.FAILED);
            state.setStatusReason(truncate(payload.getFailReason()));
            log.warn("KG 任务落终态（worker 回报）articleId={} kind={} err={}",
                    payload.getArticleId(), payload.getErrorKind(), payload.getMessage());
        } else {
            // TRANSIENT/RATE_LIMITED：留 PENDING，由发布器下轮重发（D6 对账）
            log.info("KG 任务瞬时失败计次（worker 回报）articleId={} failCount={} kind={} err={}",
                    payload.getArticleId(), failCount, payload.getErrorKind(), payload.getMessage());
        }
        stateRepository.save(state);
        if (KgExtractFailedPayload.ERROR_KIND_RATE_LIMITED.equals(payload.getErrorKind())) {
            KgExtractPublisher publisher = publisherProvider.getIfAvailable();
            if (publisher != null) {
                publisher.markRateLimited();
            }
        }
        return true;
    }

    /** 证据行 upsert：一篇文章一版（UNIQUE article_id），改稿重抽覆盖旧版（D7） */
    private void upsertEvidence(KgExtractDonePayload payload, String hash) {
        KgEvidence evidence = evidenceRepository.findByArticleId(payload.getArticleId())
                .orElseGet(() -> KgEvidence.builder().articleId(payload.getArticleId()).build());
        evidence.setContentHash(hash);
        evidence.setPayload(objectMapper.writeValueAsString(payload.getExtraction()));
        evidence.setModel(payload.getModel());
        evidence.setExtractedAt(OffsetDateTime.now());
        evidenceRepository.save(evidence);
    }

    private static int sizeOf(java.util.Collection<?> collection) {
        return collection == null ? 0 : collection.size();
    }

    /** status_reason 截断至 200 列宽（日志红线：不含正文，仅摘要与异常消息） */
    private static String truncate(String reason) {
        if (reason == null) {
            return null;
        }
        return reason.length() <= 200 ? reason : reason.substring(0, 200);
    }
}
