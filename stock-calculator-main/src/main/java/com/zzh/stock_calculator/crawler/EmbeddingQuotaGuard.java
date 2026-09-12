package com.zzh.stock_calculator.crawler;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 三类熔断护栏（设计文档 §6.1）：配额类（429 → 次日 00:00 UTC 熔断）、
 * 瞬时类（批级退避后当次退出，由调用方处理）、致命类（401/403/400 → fatal 停机）。
 *
 * <p>内存态：进程重启丢失无妨（重启后首个 429 重新置位，最多浪费一次试探调用）。
 * daily-max-articles 保险丝约束发布端全部下发（增量监听与对账扫缺统一走 tryAcquireBackfill 记账，§4.5 额度上移）。
 */
public class EmbeddingQuotaGuard {

    private final int dailyMaxArticles;

    private final AtomicInteger todayCount = new AtomicInteger(0);

    /** UTC 日切基准；包私有便于单元测试模拟跨日 */
    volatile LocalDate utcDay = LocalDate.now(ZoneOffset.UTC);

    private volatile Instant exhaustedUntil = Instant.EPOCH;

    private volatile boolean fatal = false;

    private volatile String fatalReason;

    public EmbeddingQuotaGuard(int dailyMaxArticles) {
        this.dailyMaxArticles = dailyMaxArticles;
    }

    /**
     * 回填记账：当批处理后累计将超出日上限时拒绝（到达即退出，下小时续）。
     */
    public synchronized boolean tryAcquireBackfill(int count) {
        rollDay();
        int used = todayCount.get();
        if (used + count > dailyMaxArticles) {
            return false;
        }
        todayCount.addAndGet(count);
        return true;
    }

    /** 配额类熔断：置位至次日 00:00 UTC（免费额度刷新点，宁可保守多等） */
    public void markRateLimited() {
        this.exhaustedUntil = LocalDate.now(ZoneOffset.UTC).plusDays(1)
                .atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    public boolean isRateLimited() {
        return Instant.now().isBefore(exhaustedUntil);
    }

    public Instant getExhaustedUntil() {
        return exhaustedUntil;
    }

    /** 致命类（token 失效/参数错误）：停止所有任务，人工修复配置后重启 */
    public void markFatal(String reason) {
        this.fatal = true;
        this.fatalReason = reason;
    }

    public boolean isFatal() {
        return fatal;
    }

    public String getFatalReason() {
        return fatalReason;
    }

    /** 护栏是否放行新一批处理 */
    public boolean isAvailable() {
        return !fatal && !isRateLimited();
    }

    public int getTodayCount() {
        return todayCount.get();
    }

    public int getDailyMaxArticles() {
        return dailyMaxArticles;
    }

    /** UTC 日切：跨日清零计数器（额度 00:00 UTC 重置） */
    private void rollDay() {
        LocalDate now = LocalDate.now(ZoneOffset.UTC);
        if (!now.equals(utcDay)) {
            utcDay = now;
            todayCount.set(0);
        }
    }
}
