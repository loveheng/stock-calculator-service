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
 * KG 抽取任务发布端（docs/ai-pipeline/cls-news-kg.md §7，KgExtractTask 调度入口）：
 * 扫描最新汇编候选（crawl 基包 ClsArticleQueryApi），按「未终态」过滤后逐条下发
 * task.kg.extract——每天产 1 条、dispatch-limit=3 即 3 天追赶窗口，断档自愈（D6）。
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
     * 扫描未终态汇编并发布任务（KgExtractTask 调度入口）。
     * @return 已发布任务数（供调度日志）
     */
    public int publishPendingBatch() {
        Instant until = rateLimitedUntil;
        if (Instant.now().isBefore(until)) {
            log.info("LLM 限流冷却中（至 {}），本轮 KG 任务发布跳过", until);
            return 0;
        }
        int limit = properties.getDigest().getDispatchLimit();
        int scanWindow = limit * properties.getDigest().getScanMultiplier();
        List<ClsArticleQueryApi.DigestArticle> candidates = articleQueryApi.latestDigestArticles(
                properties.getDigest().getTitleKeyword(), scanWindow);
        if (candidates.isEmpty()) {
            log.info("KG 发布扫描零候选（keyword={}）", properties.getDigest().getTitleKeyword());
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
        log.info("KG 任务发布完成 dispatched={} scan={}（未终态每日重发，D6 对账）", dispatched, candidates.size());
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
