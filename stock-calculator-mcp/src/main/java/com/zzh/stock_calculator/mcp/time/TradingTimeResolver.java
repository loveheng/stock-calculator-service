package com.zzh.stock_calculator.mcp.time;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 业务/交易日时间解析：交易日偏移（上一个交易日/未来5个开盘日/前20个交易日）、
 * 盘中状态时段（盘前/集合竞价/早盘/午后/盘后/休市）、交割结算（T+1/T+0/T+2）。
 * 交易日历以 quote_daily 真实成交日为事实源（TradingCalendarService），周末节假日已剔除。
 */
@Component
@RequiredArgsConstructor
public class TradingTimeResolver {

    private final TradingCalendarService calendar;

    private static final Pattern OFFSET = Pattern.compile(
            "(?:前|过去|上个)?\\s*([一二两三四五六七八九十百半\\d]+)\\s*个?交易日|上一个交易日|下一(?:个)?交易日|未来\\s*([一二两三四五六七八九十百半\\d]+)\\s*个?(?:开盘|交易)日");
    private static final Pattern TPLUS = Pattern.compile("[Tt]\\+(\\d)");

    private static final Map<String, LocalTime[]> SESSIONS = Map.ofEntries(
            Map.entry("集合竞价", new LocalTime[]{LocalTime.of(9, 15), LocalTime.of(9, 25)}),
            Map.entry("盘前", new LocalTime[]{LocalTime.of(9, 15), LocalTime.of(9, 30)}),
            Map.entry("早盘", new LocalTime[]{LocalTime.of(9, 30), LocalTime.of(11, 30)}),
            Map.entry("早盘收盘", new LocalTime[]{LocalTime.of(11, 30), LocalTime.of(11, 30)}),
            Map.entry("午后", new LocalTime[]{LocalTime.of(13, 0), LocalTime.of(15, 0)}),
            Map.entry("午后开盘", new LocalTime[]{LocalTime.of(13, 0), LocalTime.of(13, 0)}),
            Map.entry("收盘", new LocalTime[]{LocalTime.of(15, 0), LocalTime.of(15, 0)}),
            Map.entry("盘后", new LocalTime[]{LocalTime.of(15, 0), LocalTime.of(17, 0)}));

    public Optional<TimeRange> resolve(String text) {
        LocalDateTime now = LocalDateTime.now();
        LocalDate today = now.toLocalDate();

        // —— T+N 交割 ——
        Matcher m = TPLUS.matcher(text);
        if (m.find()) {
            int n = Integer.parseInt(m.group(1));
            LocalDate t = calendar.isTradingDay(today) ? today : calendar.nextTradingDay(today.minusDays(1));
            LocalDate settle = calendar.settleDate(t, n);
            return Optional.of(TimeRange.point(settle.atStartOfDay())
                    .toBuilder().note("T+" + n + "，按成交日 " + t).build());
        }

        // —— 盘中时段：定位基准交易日 ——
        for (var e : SESSIONS.entrySet()) {
            if (text.contains(e.getKey())) {
                LocalDate base = sessionBaseDay(text, today);
                LocalTime[] se = e.getValue();
                boolean point = se[0].equals(se[1]);
                TimeRange r = point
                        ? TimeRange.point(LocalDateTime.of(base, se[0]))
                        : TimeRange.range(LocalDateTime.of(base, se[0]), LocalDateTime.of(base, se[1]));
                return Optional.of(r.toBuilder().note("基准交易日 " + base).build());
            }
        }
        if (text.contains("休市") || text.contains("停盘")) {
            // 休市区间：从明天起往后找最近的非交易日（简化：给未来 7 天内非交易日列表语义，返回首个非交易日整天）
            LocalDate d = today.plusDays(1);
            for (int i = 0; i < 30; i++) {
                if (!calendar.isTradingDay(d)) {
                    return Optional.of(TimeRange.range(d.atStartOfDay(), d.atTime(LocalTime.MAX))
                            .toBuilder().note("最近的休市日").build());
                }
                d = d.plusDays(1);
            }
        }

        // —— 交易日偏移 ——
        if (text.contains("上一个交易日") || text.contains("上个交易日")) {
            return Optional.of(TimeRange.point(calendar.prevTradingDay(today).atStartOfDay()));
        }
        if (text.contains("下一个交易日") || text.contains("下个交易日")) {
            return Optional.of(TimeRange.point(calendar.nextTradingDay(today).atStartOfDay()));
        }
        m = OFFSET.matcher(text);
        if (m.find()) {
            int n = cnNum(m.group(1) != null ? m.group(1) : m.group(2));
            if (text.contains("未来") || text.contains("开盘日") && !text.contains("前")) {
                LocalDate end = calendar.plusTradingDays(today, n);
                return Optional.of(TimeRange.range(today.atStartOfDay(), end.atTime(LocalTime.MAX))
                        .toBuilder().note("未来" + n + "个交易日，止于 " + end).build());
            }
            LocalDate start = calendar.minusTradingDays(today, n);
            return Optional.of(TimeRange.range(start.atStartOfDay(), now)
                    .toBuilder().note("前推" + n + "个交易日，起于 " + start).build());
        }
        return Optional.empty();
    }

    private LocalDate sessionBaseDay(String text, LocalDate today) {
        if (text.contains("明天")) {
            return calendar.nextTradingDay(today);
        }
        if (text.contains("昨天")) {
            return calendar.prevTradingDay(today);
        }
        return calendar.isTradingDay(today) ? today : calendar.prevTradingDay(today);
    }

    private int cnNum(String s) {
        if (s == null) {
            return 1;
        }
        if (s.matches("\\d+")) {
            return Integer.parseInt(s);
        }
        Map<String, Integer> m = Map.ofEntries(Map.entry("一", 1), Map.entry("两", 2), Map.entry("二", 2),
                Map.entry("三", 3), Map.entry("四", 4), Map.entry("五", 5),
                Map.entry("六", 6), Map.entry("七", 7), Map.entry("八", 8),
                Map.entry("九", 9), Map.entry("十", 10), Map.entry("半", 1),
                Map.entry("十五", 15), Map.entry("二十", 20), Map.entry("三十", 30));
        if (m.containsKey(s)) {
            return m.get(s);
        }
        if (s.contains("十")) {
            String[] parts = s.split("十");
            int tens = parts[0].isEmpty() ? 1 : m.getOrDefault(parts[0], 1);
            int ones = parts.length > 1 ? m.getOrDefault(parts[1], 0) : 0;
            return tens * 10 + ones;
        }
        return 1;
    }
}
