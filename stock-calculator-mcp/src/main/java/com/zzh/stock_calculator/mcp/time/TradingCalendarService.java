package com.zzh.stock_calculator.mcp.time;

import com.zzh.stock_calculator.mcp.quote.QuoteDailyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A 股交易日历：以 quote_daily 真实成交日为事实源（数据已有的日期即交易日），
 * 未来日期（库中尚无数据）用「周一~周五 + 内置法定节假日表」兜底推算。
 */
@Service
@RequiredArgsConstructor
public class TradingCalendarService {

    private final QuoteDailyRepository quoteDailyRepository;

    /** 兜底节假日表（国务院安排惯例日，精确到 2026；之后的年份只剔周末，遇偏差交人工确认） */
    private static final Set<LocalDate> HOLIDAYS_2025_2026 = Set.of(
            // 2026 国庆中秋（10-01~10-07 邻近中秋）
            LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 2), LocalDate.of(2026, 10, 3),
            LocalDate.of(2026, 10, 4), LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 6),
            LocalDate.of(2026, 10, 7),
            // 2026 春节
            LocalDate.of(2026, 2, 16), LocalDate.of(2026, 2, 17), LocalDate.of(2026, 2, 18),
            LocalDate.of(2026, 2, 19), LocalDate.of(2026, 2, 20),
            // 2026 清明/劳动/端午
            LocalDate.of(2026, 4, 6), LocalDate.of(2026, 5, 1), LocalDate.of(2026, 6, 19));

    private volatile Set<LocalDate> tradingDaysCache;

    public boolean isTradingDay(LocalDate d) {
        if (!loadTradingDays().isEmpty()) {
            return loadTradingDays().contains(d);
        }
        return fallbackIsTradingDay(d);
    }

    /** 下一交易日（严格大于 d） */
    public LocalDate nextTradingDay(LocalDate d) {
        LocalDate cur = d.plusDays(1);
        for (int i = 0; i < 30; i++) {
            if (isTradingDay(cur)) {
                return cur;
            }
            cur = cur.plusDays(1);
        }
        return null;
    }

    /** 上一交易日（严格小于 d） */
    public LocalDate prevTradingDay(LocalDate d) {
        LocalDate cur = d.minusDays(1);
        for (int i = 0; i < 30; i++) {
            if (isTradingDay(cur)) {
                return cur;
            }
            cur = cur.minusDays(1);
        }
        return null;
    }

    /** 未来 n 个开盘日（不含 d，逐个跳过非交易日） */
    public LocalDate plusTradingDays(LocalDate d, int n) {
        LocalDate cur = d;
        for (int i = 0; i < n; i++) {
            cur = nextTradingDay(cur);
            if (cur == null) {
                return null;
            }
        }
        return cur;
    }

    /** 前推 n 个交易日（不含 d） */
    public LocalDate minusTradingDays(LocalDate d, int n) {
        LocalDate cur = d;
        for (int i = 0; i < n; i++) {
            cur = prevTradingDay(cur);
            if (cur == null) {
                return null;
            }
        }
        return cur;
    }

    /** T+N 交割日：成交日 T 之后 n 个交易日 */
    public LocalDate settleDate(LocalDate tradeDay, int n) {
        return n == 0 ? tradeDay : plusTradingDays(tradeDay, n);
    }

    private boolean fallbackIsTradingDay(LocalDate d) {
        DayOfWeek w = d.getDayOfWeek();
        if (w == DayOfWeek.SATURDAY || w == DayOfWeek.SUNDAY) {
            return false;
        }
        return !HOLIDAYS_2025_2026.contains(d);
    }

    private Set<LocalDate> loadTradingDays() {
        Set<LocalDate> cache = tradingDaysCache;
        if (cache != null) {
            return cache;
        }
        try {
            List<java.sql.Date> rows = quoteDailyRepository.findDistinctTradeDates();
            Set<LocalDate> days = new HashSet<>();
            for (java.sql.Date row : rows) {
                days.add(row.toLocalDate());
            }
            if (!days.isEmpty()) {
                tradingDaysCache = days;
            }
            return days;
        } catch (Exception e) {
            // 库不可用时走兜底日历，不让时间工具整体不可用
            return Set.of();
        }
    }
}
