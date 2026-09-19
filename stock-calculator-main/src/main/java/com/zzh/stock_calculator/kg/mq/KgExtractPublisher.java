package com.zzh.stock_calculator.kg.mq;

import com.zzh.stock_calculator.crawler.ClsArticleQueryApi;
import com.zzh.stock_calculator.crawler.TaskDispatchApi;
import com.zzh.stock_calculator.kg.config.KgProperties;
import com.zzh.stock_calculator.kg.entity.ClsArticleKg;
import com.zzh.stock_calculator.kg.entity.KgTaskStatus;
import com.zzh.stock_calculator.kg.repository.ClsArticleKgRepository;
import com.zzh.stock_calculator.kg.util.KgHashes;
import com.zzh.stockcalc.contract.MessageType;
import com.zzh.stockcalc.contract.message.KgExtractTask;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * KG 抽取任务发布端（docs/ai-pipeline/cls-news-kg.md §7，KgExtractTask/KgBackfillTask 调度入口）：
 * 双扫描入口共用发布核——daily 最新优先（追赶窗口 D6）/ backfill 最旧优先（历史回填二期），
 * 过滤未终态后逐条下发 task.kg.extract——每天产 1 条、dispatch-limit=3 即 3 天追赶窗口，断档自愈。
 * <p>状态行即游标：缺行补建 PENDING；PENDING 未终态每轮重发（at-least-once + 幂等摄取）；
 * DONE 行 content_hash 与源表不符 = 源站改稿 → 置回 PENDING 重抽；FAILED 终态跳过。</p>
 * <p>发布端熔断（同 announcement §4.5）：worker 回报 RATE_LIMITED → markRateLimited
 * 暂停发布窗口，冷却结束自动恢复。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KgExtractPublisher {

    private final ClsArticleQueryApi articleQueryApi;
    private final ClsArticleKgRepository stateRepository;
    private final TaskDispatchApi taskDispatchApi;
    private final KgProperties properties;

    /** 发布端熔断窗口截止时刻（RATE_LIMITED 上报后顺延） */
    private volatile Instant rateLimitedUntil = Instant.EPOCH;

    /**
     * 每日发布入口（KgExtractTask 调度）：最新优先 DESC 扫描，dispatch-limit 即追赶窗口（D6）。
     * @return 已发布任务数（供调度日志）
     */
    public int publishPendingBatch() {
        int limit = properties.getDigest().getDispatchLimit();
        int scanWindow = limit * properties.getDigest().getScanMultiplier();
        return publishFromScan(articleQueryApi.latestDigestArticles(
                properties.getDigest().getTitleKeyword(), scanWindow), limit, "daily");
    }

    /**
     * 历史回填发布入口（二期，job.kg.backfill / KgBackfillTask）：最旧优先 ASC 扫描按批补发，
     * 扫描窗口即游标——终态随融合累积、窗口自然前滑，追平后窗口内全 DONE 零下发空转。
     * 与 daily 共用任务队列/结果通道/限流熔断窗口，dispatch 语义幂等（D6/D7）。
     * @return 已发布任务数（供调度日志）
     */
    public int publishBackfillBatch() {
        int limit = properties.getBackfill().getBatchSize();
        int scanWindow = limit * properties.getBackfill().getScanMultiplier();
        return publishFromScan(articleQueryApi.oldestDigestArticles(
                properties.getDigest().getTitleKeyword(), scanWindow), limit, "backfill");
    }

    /** 共享发布核：限流熔断门 → 未终态过滤（状态即游标）→ 逐条下发（at-least-once，幂等摄取） */
    private int publishFromScan(List<ClsArticleQueryApi.DigestArticle> candidates, int limit, String source) {
        Instant until = rateLimitedUntil;
        if (Instant.now().isBefore(until)) {
            log.info("LLM 限流冷却中（至 {}），本轮 KG 任务发布跳过（{}）", until, source);
            return 0;
        }
        if (candidates.isEmpty()) {
            log.info("KG 发布扫描零候选（source={} keyword={}）", source, properties.getDigest().getTitleKeyword());
            return 0;
        }
        Map<Long, ClsArticleKg> stateByArticle = stateRepository
                .findByArticleIdIn(candidates.stream().map(ClsArticleQueryApi.DigestArticle::articleId).toList())
                .stream()
                .collect(Collectors.toMap(ClsArticleKg::getArticleId, Function.identity()));
        int dispatched = 0;
        for (ClsArticleQueryApi.DigestArticle article : candidates) {
            if (dispatched >= limit) {
                break;
            }
            String hash = KgHashes.sha256Hex(article.content());
            ClsArticleKg state = stateByArticle.get(article.articleId());
            if (state != null && shouldSkip(state, hash, article.articleId())) {
                continue;
            }
            upsertPendingState(state, article, hash);
            boolean ok = taskDispatchApi.dispatchTask(MessageType.TASK_KG_EXTRACT,
                    KgExtractTask.builder()
                            .articleId(article.articleId())
                            .contentHash(hash)
                            .title(article.title())
                            .ctime(article.ctime())
                            .content(article.content())
                            .build());
            if (ok) {
                dispatched++;
            }
        }
        log.info("KG 任务发布完成 source={} dispatched={} scan={}（未终态重发，D6 对账）",
                source, dispatched, candidates.size());
        return dispatched;
    }

    /** RATE_LIMITED 上报 → 暂停发布窗口（kg.process.rate-limit-cooldown-minutes） */
    public void markRateLimited() {
        long minutes = properties.getProcess().getRateLimitCooldownMinutes();
        rateLimitedUntil = Instant.now().plusSeconds(minutes * 60);
        log.warn("LLM 限流上报，KG 任务发布暂停 {} 分钟", minutes);
    }

    /** 终态跳过判定：FAILED 恒跳；DONE 且 hash 未变跳（改稿则置回 PENDING 由下方重发） */
    private boolean shouldSkip(ClsArticleKg state, String hash, Long articleId) {
        if (state.getStatus() == KgTaskStatus.FAILED) {
            return true;
        }
        if (state.getStatus() == KgTaskStatus.DONE && hash.equals(state.getContentHash())) {
            return true;
        }
        if (state.getStatus() == KgTaskStatus.DONE) {
            log.info("汇编正文改稿，置回 PENDING 重抽, articleId={}", articleId);
            state.setStatus(KgTaskStatus.PENDING);
            state.setContentHash(hash);
            state.setStatusReason(null);
            stateRepository.save(state);
        }
        return false;
    }

    /** 缺行补建 PENDING（状态即游标；@CreationTimestamp 落 created_at 供 PENDING_AGE 巡检） */
    private void upsertPendingState(ClsArticleKg state, ClsArticleQueryApi.DigestArticle article, String hash) {
        if (state == null) {
            stateRepository.save(ClsArticleKg.builder()
                    .articleId(article.articleId())
                    .contentHash(hash)
                    .build());
        } else if (state.getContentHash() == null || !state.getContentHash().equals(hash)) {
            state.setContentHash(hash);
            stateRepository.save(state);
        }
    }
}
