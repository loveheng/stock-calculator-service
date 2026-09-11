package com.zzh.stock_calculator.announcement.service;

import com.pgvector.PGvector;
import com.zzh.stockcalc.contract.MessageType;
import com.zzh.stockcalc.contract.message.EmbeddingComputeResult;
import com.zzh.stockcalc.contract.message.EmbeddingComputeTask;
import com.zzh.stock_calculator.announcement.entity.Announcement;
import com.zzh.stock_calculator.announcement.entity.AnnouncementStatus;
import com.zzh.stock_calculator.announcement.repository.AnnouncementRepository;
import com.zzh.stock_calculator.crawler.AnnouncementEmbeddingApi;
import com.zzh.stock_calculator.crawler.EmbeddingQuotaGuard;
import com.zzh.stock_calculator.crawler.TaskDispatchApi;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 公告嵌入 MQ 双面服务（设计文档 §4.3 D7 两段式，§8 阶段 4 任务 3）：
 * crawler.AnnouncementEmbeddingApi 的 announcement 域实现，承接两端——
 * 发布面 dispatchEmbeddingTask：task.embedding.compute(kind=announcement, text=摘要) 下发，
 * 额度在发布端扣减（D8，复用共享 EmbeddingQuotaGuard 单例）；发布经 TaskDispatchApi
 * （datasvc.mq.enabled 门控，MQ 关闭时空转返回 false，PENDING 行留对账）。
 * 消费面 applyEmbeddingResult：确定性 UUID 向量 upsert + 状态行 DONE 同事务成对写，
 * 绝不经 VectorStore.add()（其内部会重新发起 CF 嵌入，双份烧额度）。
 * <p>指纹判重（cls EmbeddingResultService 同语义）：向量行已存在且 metadata.kind=announcement
 * 且 content=当前摘要 → 重复/晚到结果跳过，防旧结果覆盖新摘要；kind 缺失（B8 前存量行）
 * → 覆盖写完成 metadata 增强。DONE 判重在发布面（force 供对账器补存量）。</p>
 * <p>门控：embedding.enabled 功能开关 @Value 平移（EmbeddingGate.isFeatureEnabled 同口径，
 * 红线不可引 crawler.embedding.config）；CF 凭据齐备性属计算端 worker（v1.4 细化）。
 * UPSERT SQL 与 EmbeddingResultService/PgVectorStore.insertOrUpdateBatch 逐字节一致
 * （跨模块红线不可引 crawler.embedding.service，本地副本，三处同步修改）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AnnouncementEmbeddingMqService implements AnnouncementEmbeddingApi {

    /** 与 cls 共用 CF bge-m3；EmbeddingProperties 属 crawler 子包（Modulith 红线不可引），此处硬编码 */
    private static final String EMBEDDING_MODEL = "@cf/baai/bge-m3";

    private static final String METADATA_KIND = "announcement";

    static final String UPSERT_VECTOR_SQL = """
            INSERT INTO public.vector_store (id, content, metadata, embedding) VALUES (?, ?, ?::jsonb, ?)
            ON CONFLICT (id) DO UPDATE SET content = ?, metadata = ?::jsonb, embedding = ?
            """;

    private static final String SELECT_VECTOR_ROW_SQL =
            "SELECT content, metadata->>'kind' AS kind FROM vector_store WHERE id = ?";

    private final AnnouncementRepository announcementRepository;
    private final EmbeddingQuotaGuard quotaGuard;
    private final TaskDispatchApi taskDispatchApi;
    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;

    /** 发布面功能开关（与 EmbeddingGate.isFeatureEnabled 同口径，@Value 平移） */
    @Value("${embedding.enabled:false}")
    private boolean embeddingEnabled;

    @Override
    public boolean dispatchEmbeddingTask(Long announcementId, boolean force) {
        if (!embeddingEnabled) {
            log.debug("embedding feature disabled, announcement embedding dispatch skipped, id={}", announcementId);
            return false;
        }
        Announcement announcement = announcementRepository.findById(announcementId).orElse(null);
        if (announcement == null) {
            log.warn("announcement not found, embedding task skipped, id={}", announcementId);
            return false;
        }
        String summary = announcement.getSummary();
        if (!StringUtils.hasText(summary)) {
            // 与进程内「摘要为空，不可向量化」同语义：MQ 面转业务跳过（蒸馏未完成不占额度）
            log.info("announcement summary blank, embedding task skipped, id={}", announcementId);
            return false;
        }
        if (announcement.getStatus() == AnnouncementStatus.DONE && !force) {
            // 向量+DONE 同事务成对写（消费面），DONE 即已嵌入；force 供对账器补存量缺向量行
            log.debug("announcement already DONE, dispatch skipped, id={}", announcementId);
            return false;
        }
        if (!quotaGuard.isAvailable()) {
            log.warn("quota guard unavailable (fatal/rate-limited), dispatch deferred, id={}", announcementId);
            return false;
        }
        if (!quotaGuard.tryAcquireBackfill(1)) {
            log.warn("daily quota exhausted, dispatch deferred, id={}, todayCount={}",
                    announcementId, quotaGuard.getTodayCount());
            return false;
        }
        boolean dispatched = taskDispatchApi.dispatchTask(MessageType.TASK_EMBEDDING_COMPUTE,
                EmbeddingComputeTask.builder()
                        .kind(EmbeddingComputeTask.KIND_ANNOUNCEMENT)
                        .refId(announcementId)
                        .text(summary.trim())
                        .build());
        if (dispatched) {
            log.info("announcement embedding task dispatched, id={}, force={}, todayCount={}",
                    announcementId, force, quotaGuard.getTodayCount());
        }
        return dispatched;
    }

    @Override
    public boolean applyEmbeddingResult(EmbeddingComputeResult result) {
        if (result == null || result.getRefId() == null) {
            log.warn("announcement embedding result missing refId, skipped");
            return false;
        }
        Announcement announcement = announcementRepository.findById(result.getRefId()).orElse(null);
        if (announcement == null) {
            // 发布端只对存在行下发；此为投递窗口内被删的兜底，丢弃（无对账环风险）
            log.warn("announcement embedding result row not found, refId={}, skipped", result.getRefId());
            return false;
        }
        String summary = announcement.getSummary();
        if (!StringUtils.hasText(summary)) {
            log.warn("announcement summary blank, embedding result skipped, refId={}", result.getRefId());
            return false;
        }
        String text = summary.trim();
        UUID rowId = UUID.fromString(AnnouncementEmbeddingService.deterministicUuid(announcement.getId()));
        if (vectorRowMatches(rowId, text)) {
            // 重复/晚到结果：向量已在且与当前摘要一致 → 跳过（旧结果不得覆盖，cls 同语义）
            log.debug("announcement vector already present with matching summary, result ignored, refId={}",
                    announcement.getId());
            return false;
        }

        transactionTemplate.executeWithoutResult(tx -> {
            String model = resolveModel(result);
            String metadataJson = objectMapper.writeValueAsString(Map.of(
                    "announcementId", announcement.getId(),
                    "adjunctUrl", announcement.getAdjunctUrl() == null ? "" : announcement.getAdjunctUrl(),
                    "secCode", announcement.getSecCode() == null ? "" : announcement.getSecCode(),
                    "model", model,
                    "kind", METADATA_KIND,
                    "annDate", announcement.getSeDate() == null ? "" : announcement.getSeDate().toString()));
            PGvector vector = new PGvector(toFloatArray(result.getVector()));
            jdbcTemplate.update(UPSERT_VECTOR_SQL, rowId, text, metadataJson, vector,
                    text, metadataJson, vector);
            announcement.setStatus(AnnouncementStatus.DONE);
            announcement.setStatusReason(null);
            announcementRepository.save(announcement);
        });
        log.info("announcement vector applied, refId={}, model={}, tokensUsed={}",
                announcement.getId(), resolveModel(result), result.getTokensUsed());
        return true;
    }

    /**
     * 指纹判重：同 id 向量行存在且 kind=announcement、content=当前摘要 → true。
     * kind 缺失（B8 前存量）或 content 不一致（摘要重蒸馏）→ false，走覆盖 upsert。
     */
    private boolean vectorRowMatches(UUID rowId, String text) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(SELECT_VECTOR_ROW_SQL, rowId);
        if (rows.isEmpty()) {
            return false;
        }
        Object kind = rows.get(0).get("kind");
        Object content = rows.get(0).get("content");
        return METADATA_KIND.equals(kind) && text.equals(content);
    }

    /** 模型留档取值：worker 回报优先，缺省回退共享 bge-m3 常量（与进程内同值） */
    private String resolveModel(EmbeddingComputeResult result) {
        if (result.getModel() != null && !result.getModel().isBlank()) {
            return result.getModel();
        }
        return EMBEDDING_MODEL;
    }

    private float[] toFloatArray(List<Float> vector) {
        float[] array = new float[vector.size()];
        for (int i = 0; i < vector.size(); i++) {
            Float v = vector.get(i);
            array[i] = v == null ? 0f : v;
        }
        return array;
    }
}
