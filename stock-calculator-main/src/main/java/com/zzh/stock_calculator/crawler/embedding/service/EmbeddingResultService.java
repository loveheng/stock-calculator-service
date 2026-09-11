package com.zzh.stock_calculator.crawler.embedding.service;

import com.pgvector.PGvector;
import com.zzh.stockcalc.contract.message.EmbeddingComputeResult;
import com.zzh.stockcalc.contract.message.EmbeddingComputeTask;
import com.zzh.stock_calculator.crawler.AnnouncementEmbeddingApi;
import com.zzh.stock_calculator.crawler.embedding.config.EmbeddingProperties;
import com.zzh.stock_calculator.crawler.embedding.entity.ClsArticleEmbedding;
import com.zzh.stock_calculator.crawler.embedding.entity.EmbeddingStatus;
import com.zzh.stock_calculator.crawler.embedding.repository.ClsArticleEmbeddingRepository;
import com.zzh.stock_calculator.crawler.entity.ClsArticle;
import com.zzh.stock_calculator.crawler.repository.ClsArticleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * result.embedding.done 消费侧落库（设计文档 §4.3/§4.5，阶段 3 任务化）：
 * worker 已完成 CF 计算，本类只做「确定性 UUID 向量 upsert + 状态行 DONE 落账」，
 * 绝不经 VectorStore.add()（其内部会重新发起 CF 嵌入，双份烧额度）——直接以与
 * PgVectorStore.insertOrUpdateBatch（Spring AI 2.0.1，EmbeddingConfig 默认
 * public/vector_store）完全一致的 SQL 写表，embedding 参数同为 PGvector。
 * <p>kind=announcement（阶段 4 任务 3，D7 两段式）：维度校验后委托
 * AnnouncementEmbeddingApi 端口由 announcement 域落账，cls 路径不变。</p>
 *
 * <p>幂等锚点（§4.3）：向量行主键 = ArticleEmbeddingService.deterministicUuid(refId)，
 * ON CONFLICT (id) DO UPDATE → 重复消费无副作用；状态行先查指纹，DONE 且 contentHash
 * 未变 → 重复投递直接跳过（防「晚到的旧结果覆盖新向量」：文章改写重算后，旧任务的
 * 重复回报不得回写旧向量）。contentHash 由主服务按当前文章文本重算（worker 不回传指纹）。
 *
 * <p>事务语义与进程内路径一致（S3）：向量写入 + 状态行同一事务（TransactionTemplate），
 * 同批原子——向量写入回滚则状态不落。业务性跳过（未知 kind/文章缺失/空文本/维度不符）
 * 仅告警后正常返回（消息 ack 丢弃，不进重试环；缺失文章本就无人下发任务，无对账环风险）；
 * 基础设施异常原样重抛，由消费端分流进 TTL 重试环（§4.2）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmbeddingResultService {

    /** 与 PgVectorStore.insertOrUpdateBatch 逐字节一致的 upsert 语句（表名取其默认 public.vector_store） */
    static final String UPSERT_VECTOR_SQL = """
            INSERT INTO public.vector_store (id, content, metadata, embedding) VALUES (?, ?, ?::jsonb, ?)
            ON CONFLICT (id) DO UPDATE SET content = ?, metadata = ?::jsonb, embedding = ?
            """;

    private static final String DEFAULT_MODEL = "@cf/baai/bge-m3";

    private final ClsArticleRepository articleRepository;
    private final ClsArticleEmbeddingRepository embeddingRepository;
    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final EmbeddingProperties properties;
    private final ObjectMapper objectMapper;
    /** 公告嵌入落账端口（announcement 域实现；ObjectProvider 防实现缺失阻启动） */
    private final ObjectProvider<AnnouncementEmbeddingApi> announcementEmbeddingProvider;

    /**
     * 应用向量化计算结果（幂等，可重复调用）。
     * 返回 false 表示业务性跳过（消费端可安全 ack）；基础设施失败抛异常交重试环。
     */
    public boolean applyComputeResult(EmbeddingComputeResult result) {
        if (result == null || result.getRefId() == null) {
            log.warn("embedding result missing refId, skipped");
            return false;
        }
        if (EmbeddingComputeTask.KIND_ANNOUNCEMENT.equals(result.getKind())) {
            // 公告分支（阶段 4 任务 3，D7 两段式）：维度校验前置后委托 announcement 域端口
            return applyAnnouncementResult(result);
        }
        if (!EmbeddingComputeTask.KIND_CLS_ARTICLE.equals(result.getKind())) {
            log.warn("unsupported embedding result kind={}, refId={}, skipped",
                    result.getKind(), result.getRefId());
            return false;
        }
        int dims = properties.getCloudflare().getDimensions();
        if (result.getVector() == null || result.getVector().size() != dims) {
            log.warn("embedding result vector dimension mismatch, refId={}, expected={}, actual={}, skipped",
                    result.getRefId(), dims, result.getVector() == null ? 0 : result.getVector().size());
            return false;
        }

        ClsArticle article = articleRepository.findById(result.getRefId()).orElse(null);
        if (article == null) {
            // 发布端对缺失文章本就不下发任务；此为投递窗口内被删的兜底，丢弃（无对账环风险）
            log.warn("embedding result article not found, refId={}, skipped", result.getRefId());
            return false;
        }
        String text = ArticleEmbeddingService.normalizeInput(article);
        if (text.isBlank()) {
            log.warn("embedding result article has empty text, refId={}, skipped", result.getRefId());
            return false;
        }
        String hash = ArticleEmbeddingService.sha256Hex(text);

        transactionTemplate.executeWithoutResult(tx -> upsertAll(result, article, text, hash));
        log.info("embedding result applied, refId={}, model={}, dims={}, tokensUsed={}",
                result.getRefId(), resolveModel(result, null), dims, result.getTokensUsed());
        return true;
    }

    /**
     * 公告分支：维度校验（与 cls 同口径）后委托 AnnouncementEmbeddingApi 落账
     * （确定性 UUID upsert + DONE 同事务在 announcement 域内，实体不外泄）。
     */
    private boolean applyAnnouncementResult(EmbeddingComputeResult result) {
        int dims = properties.getCloudflare().getDimensions();
        if (result.getVector() == null || result.getVector().size() != dims) {
            log.warn("embedding result vector dimension mismatch, kind=announcement, refId={}, expected={}, actual={}, skipped",
                    result.getRefId(), dims, result.getVector() == null ? 0 : result.getVector().size());
            return false;
        }
        AnnouncementEmbeddingApi api = announcementEmbeddingProvider.getIfAvailable();
        if (api == null) {
            log.error("announcement embedding api absent, dropped, refId={}", result.getRefId());
            return false;
        }
        return api.applyEmbeddingResult(result);
    }

    private void upsertAll(EmbeddingComputeResult result, ClsArticle article, String text, String hash) {
        ClsArticleEmbedding existing = embeddingRepository.findById(article.getId()).orElse(null);
        if (existing != null && existing.getStatus() == EmbeddingStatus.DONE
                && hash.equals(existing.getContentHash())) {
            // 重复投递/晚到的旧结果：指纹未变 → 跳过，旧向量不得覆盖新向量
            log.debug("embedding already DONE with unchanged hash, result ignored, refId={}", article.getId());
            return;
        }

        String model = resolveModel(result, existing);
        // ① 向量行：确定性 UUID upsert（同文重嵌覆盖同行，不产生重复向量）
        String docId = ArticleEmbeddingService.deterministicUuid(article.getId());
        String metadataJson = objectMapper.writeValueAsString(Map.of(
                "articleId", article.getId(),
                "ctime", article.getCtime(),
                "level", article.getLevel() == null ? "" : article.getLevel(),
                "model", model));
        PGvector vector = new PGvector(toFloatArray(result.getVector()));
        jdbcTemplate.update(UPSERT_VECTOR_SQL,
                UUID.fromString(docId), text, metadataJson, vector,
                text, metadataJson, vector);

        // ② 状态行：DONE 落账（与进程内 doEmbed 同一字段口径）
        ClsArticleEmbedding row = existing != null ? existing
                : ClsArticleEmbedding.builder().articleId(article.getId()).build();
        row.setStatus(EmbeddingStatus.DONE);
        row.setFailCount(0);
        row.setContentHash(hash);
        row.setError(null);
        row.setEmbeddedAt(OffsetDateTime.now());
        row.setModel(model);
        embeddingRepository.save(row);
    }

    /** 模型留档取值：worker 回报优先，缺省回退状态行留档 → 默认常量（与实体 Builder.Default 同值） */
    private String resolveModel(EmbeddingComputeResult result, ClsArticleEmbedding existing) {
        if (result.getModel() != null && !result.getModel().isBlank()) {
            return result.getModel();
        }
        if (existing != null && existing.getModel() != null && !existing.getModel().isBlank()) {
            return existing.getModel();
        }
        return DEFAULT_MODEL;
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
