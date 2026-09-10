package com.zzh.stock_calculator.announcement.service;

import com.zzh.stock_calculator.announcement.entity.Announcement;
import com.zzh.stock_calculator.announcement.entity.AnnouncementStatus;
import com.zzh.stock_calculator.announcement.repository.AnnouncementRepository;
import com.zzh.stock_calculator.crawler.EmbeddingQuotaGuard;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 公告向量化（设计文档 §4.8/D6）：域内复刻小路径——确定性 UUID（nameUUIDFromBytes）
 * 幂等、metadata 溯源、复用 crawler 基包 EmbeddingQuotaGuard 共享额度计数（严禁第二份独立计数）。
 * 输入 = 蒸馏摘要（200~300 字，量级与 cls 电报相当）；bge-m3 1024 维与 cls 同语义空间。
 * PgVectorStore 经 ObjectProvider 懒解（embedding 未启用时不阻塞启动）。
 * 事务：store.add 内部即调嵌入模型（网络 IO 在 add 内），TransactionTemplate 包裹
 * add + 状态 upsert 成对写入（cls S3 结论沿用）；失败仅 warn 不消费 fail_count（外部故障）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AnnouncementEmbeddingService {

    /** 与 cls 共用 CF bge-m3；EmbeddingProperties 属 crawler 子包（Modulith 红线不可引），此处硬编码 */
    private static final String EMBEDDING_MODEL = "@cf/baai/bge-m3";

    private static final String UUID_NAMESPACE_PREFIX = "announcement:";

    private final AnnouncementRepository announcementRepository;
    private final EmbeddingQuotaGuard quotaGuard;
    private final ObjectProvider<VectorStore> vectorStoreProvider;
    private final TransactionTemplate transactionTemplate;

    /** 蒸馏完成后调用：摘要向量化 + 向量/状态成对写入；外部故障保持 PENDING 下轮断点续传 */
    public void processAnnouncement(Long announcementId) {
        Announcement announcement = announcementRepository.findById(announcementId)
                .orElseThrow(() -> new IllegalStateException("announcement not found, id=" + announcementId));
        String summary = announcement.getSummary();
        if (summary == null || summary.isBlank()) {
            throw new IllegalStateException("摘要为空，不可向量化 id=" + announcementId);
        }
        VectorStore vectorStore = vectorStoreProvider.getIfAvailable();
        if (vectorStore == null) {
            log.warn("向量化未启用（EMBEDDING_ENABLED=false 或凭据缺失），保持 PENDING id={}", announcementId);
            return;
        }
        if (!quotaGuard.isAvailable() || !quotaGuard.tryAcquireBackfill(1)) {
            log.warn("嵌入额度护栏拒绝（fatal/rateLimited/日限额），保持 PENDING id={}", announcementId);
            return;
        }
        String documentId = deterministicUuid(announcementId);
        try {
            transactionTemplate.executeWithoutResult(tx -> {
                Document document = new Document(documentId, summary.trim(), Map.of(
                        "announcementId", announcementId,
                        "adjunctUrl", announcement.getAdjunctUrl() == null ? "" : announcement.getAdjunctUrl(),
                        "secCode", announcement.getSecCode() == null ? "" : announcement.getSecCode(),
                        "model", EMBEDDING_MODEL));
                vectorStore.add(List.of(document));
                announcement.setStatus(AnnouncementStatus.DONE);
                announcement.setStatusReason(null);
                announcementRepository.save(announcement);
            });
            log.info("公告向量化完成 id={} docId={}", announcementId, documentId);
        } catch (Exception e) {
            // 外部故障（CF 嵌入/向量库）：不消费 fail_count，保持 PENDING 下轮断点续传重试（cls TRANSIENT 同款语义）
            log.warn("公告向量化失败（外部故障，保持 PENDING 不计次）id={} err={}", announcementId, e.getMessage());
        }
    }

    /** 确定性文档 id：同篇重嵌覆盖同行，杜绝重复向量（cls 同款） */
    static String deterministicUuid(Long announcementId) {
        return UUID.nameUUIDFromBytes(
                (UUID_NAMESPACE_PREFIX + announcementId).getBytes(StandardCharsets.UTF_8)).toString();
    }
}
