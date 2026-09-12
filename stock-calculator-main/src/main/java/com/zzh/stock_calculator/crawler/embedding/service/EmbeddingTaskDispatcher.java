package com.zzh.stock_calculator.crawler.embedding.service;

import com.zzh.stock_calculator.crawler.EmbeddingQuotaGuard;
import com.zzh.stock_calculator.crawler.embedding.entity.ClsArticleEmbedding;
import com.zzh.stock_calculator.crawler.embedding.entity.EmbeddingStatus;
import com.zzh.stock_calculator.crawler.embedding.repository.ClsArticleEmbeddingRepository;
import com.zzh.stock_calculator.crawler.entity.ClsArticle;
import com.zzh.stock_calculator.crawler.mq.TaskPublisher;
import com.zzh.stock_calculator.crawler.repository.ClsArticleRepository;
import com.zzh.stockcalc.contract.MessageType;
import com.zzh.stockcalc.contract.message.EmbeddingComputeTask;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 增量嵌入任务发布端（设计文档 §3.2/§4.5，阶段 3 任务化）：
 * 进程内 CF 计算跨服务化为 task.embedding.compute 下发，本类只做「发布前」三件事：
 * ①取文本（与进程内嵌入同口径 normalizeInput，worker 据此计算，无需查询主服务库）；
 * ②指纹判重（DONE 且 contentHash 未变 → 不重复下发，省 CF 额度）；
 * ③额度上移（§4.5）：发布前 EmbeddingQuotaGuard 扣减放行，余额不足/熔断 → 停发，
 *   PENDING 行留给对账任务补发（D6），主链路绝不阻塞。
 * <p>不写任何 embedding 状态：DONE/FAILED 落库由 result.embedding.done 消费端承担。</p>
 * <p>常驻装配（MQ 单路径终态）：ArticleSavedEvent → 本类 → task.embedding.compute。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EmbeddingTaskDispatcher {

    private final ClsArticleRepository articleRepository;
    private final ClsArticleEmbeddingRepository embeddingRepository;
    private final EmbeddingQuotaGuard quotaGuard;
    private final TaskPublisher taskPublisher;

    /**
     * 发布单篇嵌入任务；返回 true 表示已下发（供调用方记账/测试）。
     * 所有拒绝分支（无文章/空文本/指纹未变/额度熔断）均为「不下发 + 日志」，不抛异常。
     */
    public boolean dispatchForArticle(Long articleId) {
        ClsArticle article = articleRepository.findById(articleId).orElse(null);
        if (article == null) {
            log.warn("article not found, embedding task skipped, articleId={}", articleId);
            return false;
        }

        String text = ArticleEmbeddingService.normalizeInput(article);
        if (text.isBlank()) {
            // 永久性无法嵌入：与进程内语义对齐（空文本不重试、不发 CF），仅日志留痕
            log.info("empty input text, embedding task skipped, articleId={}", articleId);
            return false;
        }

        String hash = ArticleEmbeddingService.sha256Hex(text);
        ClsArticleEmbedding existing = embeddingRepository.findById(articleId).orElse(null);
        if (existing != null && existing.getStatus() == EmbeddingStatus.DONE
                && hash.equals(existing.getContentHash())) {
            log.debug("embedding DONE with unchanged hash, dispatch skipped, articleId={}", articleId);
            return false;
        }

        if (!quotaGuard.isAvailable()) {
            log.warn("quota guard unavailable (fatal/rate-limited), dispatch deferred, articleId={}", articleId);
            return false;
        }
        if (!quotaGuard.tryAcquireBackfill(1)) {
            // 保险丝语义平移：发布端统一记账（§4.5 上移），增量下发同样占日额度计数器
            log.warn("daily quota exhausted, dispatch deferred, articleId={}, todayCount={}",
                    articleId, quotaGuard.getTodayCount());
            return false;
        }

        taskPublisher.dispatchTask(MessageType.TASK_EMBEDDING_COMPUTE,
                EmbeddingComputeTask.builder()
                        .kind(EmbeddingComputeTask.KIND_CLS_ARTICLE)
                        .refId(articleId)
                        .text(text)
                        .build());
        log.info("embedding task dispatched, articleId={}, hash={}, todayCount={}",
                articleId, hash, quotaGuard.getTodayCount());
        return true;
    }
}
