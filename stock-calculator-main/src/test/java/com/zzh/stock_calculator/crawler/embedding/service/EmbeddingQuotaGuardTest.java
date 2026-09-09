package com.zzh.stock_calculator.crawler.embedding.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * EmbeddingQuotaGuard 单测（无 Spring 上下文）：日上限保险丝、UTC 日切清零、
 * 429 熔断置位至次日 00:00 UTC、fatal 停机。
 */
class EmbeddingQuotaGuardTest {

    @Test
    @DisplayName("tryAcquireBackfill: 日上限内放行, 超限拒绝")
    void tryAcquireBackfillRespectsDailyMax() {
        EmbeddingQuotaGuard guard = new EmbeddingQuotaGuard(10);

        assertThat(guard.tryAcquireBackfill(6)).isTrue();
        assertThat(guard.tryAcquireBackfill(4)).isTrue();
        assertThat(guard.getTodayCount()).isEqualTo(10);

        // 已达上限：再要 1 条拒绝
        assertThat(guard.tryAcquireBackfill(1)).isFalse();
        assertThat(guard.getTodayCount()).isEqualTo(10);
    }

    @Test
    @DisplayName("UTC 日切: 跨日计数器清零")
    void rollDayResetsCounter() {
        EmbeddingQuotaGuard guard = new EmbeddingQuotaGuard(10);
        assertThat(guard.tryAcquireBackfill(10)).isTrue();
        assertThat(guard.tryAcquireBackfill(1)).isFalse();

        // 模拟次日（直接改包私有日切基准字段）
        guard.utcDay = LocalDate.now(ZoneOffset.UTC).plusDays(1);

        assertThat(guard.tryAcquireBackfill(1)).isTrue();
        assertThat(guard.getTodayCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("markRateLimited: 熔断至次日 00:00 UTC")
    void markRateLimitedBlocksUntilNextUtcMidnight() {
        EmbeddingQuotaGuard guard = new EmbeddingQuotaGuard(10);
        assertThat(guard.isRateLimited()).isFalse();

        guard.markRateLimited();

        assertThat(guard.isRateLimited()).isTrue();
        assertThat(guard.isAvailable()).isFalse();

        Instant expected = LocalDate.now(ZoneOffset.UTC).plusDays(1)
                .atStartOfDay(ZoneOffset.UTC).toInstant();
        // 允许 2 秒时钟误差
        long deltaSeconds = Math.abs(guard.getExhaustedUntil().getEpochSecond()
                - expected.getEpochSecond());
        assertThat(deltaSeconds).isLessThan(2);

        // 熔断点是未来时刻，且落在 (now, now+25h] 区间内
        Instant now = Instant.now();
        assertThat(guard.getExhaustedUntil()).isAfter(now);
        assertThat(guard.getExhaustedUntil())
                .isBefore(now.plus(25, ChronoUnit.HOURS));
    }

    @Test
    @DisplayName("markFatal: 停机且不可自动恢复")
    void fatalBlocksEverything() {
        EmbeddingQuotaGuard guard = new EmbeddingQuotaGuard(10);

        guard.markFatal("401 unauthorized");
        assertThat(guard.isFatal()).isTrue();
        assertThat(guard.getFatalReason()).isEqualTo("401 unauthorized");
        assertThat(guard.isAvailable()).isFalse();
    }

    @Test
    @DisplayName("isAvailable: 正常态放行")
    void availableByDefault() {
        EmbeddingQuotaGuard guard = new EmbeddingQuotaGuard(10);
        assertThat(guard.isAvailable()).isTrue();
        assertThat(guard.isFatal()).isFalse();
        assertThat(guard.isRateLimited()).isFalse();
    }
}
