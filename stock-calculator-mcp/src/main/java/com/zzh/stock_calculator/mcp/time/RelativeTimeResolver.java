package com.zzh.stock_calculator.mcp.time;

import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 相对时间解析（强依赖当前锚点 Now，全部确定性代码计算，禁止交给 LLM 推理）。
 * 覆盖：基准相对（今天/昨天/上周五/下个月底/去年同期）、时长偏移（3小时后/45天前/前推2个月）、区间窗口（最近7天/近3个季度）。
 */
@Component
public class RelativeTimeResolver {

    private static final Pattern DELTA = Pattern.compile(
            "([一二两三四五六七八九十百半\\d]+)\\s*(个)?(小时|分钟|天|日|周|星期|个月|月|季度|年)(之|以)?(前|后|内)");
    private static final Pattern WINDOW = Pattern.compile(
            "(最近|近|过去|上个|过去)\\s*([一二两三四五六七八九十百半\\d]+)\\s*(个)?(小时|天|日|周|星期|个月|月|季度|年)");
    private static final Map<String, Long> CN_NUM = Map.ofEntries(
            Map.entry("一", 1L), Map.entry("两", 2L), Map.entry("二", 2L), Map.entry("三", 3L),
            Map.entry("四", 4L), Map.entry("五", 5L), Map.entry("六", 6L), Map.entry("七", 7L),
            Map.entry("八", 8L), Map.entry("九", 9L), Map.entry("十", 10L), Map.entry("半", 0L),
            Map.entry("十五", 15L), Map.entry("三十", 30L));

    private static final Map<String, DayOfWeek> WEEKDAY = Map.ofEntries(
            Map.entry("周一", DayOfWeek.MONDAY), Map.entry("周二", DayOfWeek.TUESDAY),
            Map.entry("周三", DayOfWeek.WEDNESDAY), Map.entry("周四", DayOfWeek.THURSDAY),
            Map.entry("周五", DayOfWeek.FRIDAY), Map.entry("周六", DayOfWeek.SATURDAY),
            Map.entry("周日", DayOfWeek.SUNDAY), Map.entry("星期一", DayOfWeek.MONDAY),
            Map.entry("星期二", DayOfWeek.TUESDAY), Map.entry("星期三", DayOfWeek.WEDNESDAY),
            Map.entry("星期四", DayOfWeek.THURSDAY), Map.entry("星期五", DayOfWeek.FRIDAY),
            Map.entry("星期六", DayOfWeek.SATURDAY), Map.entry("星期日", DayOfWeek.SUNDAY));

    public Optional<TimeRange> resolve(String text) {
        LocalDateTime now = LocalDateTime.now();

        // —— 固定词表（顺序敏感：先长词后短词） ——
        TimeRange r = fixed(text, now);
        if (r != null) {
            return Optional.of(r);
        }

        // —— delta 偏移：3小时后 / 45天前 / 前推2个月 ——
        Matcher m = DELTA.matcher(text);
        if (m.find()) {
            long n = parseNum(m.group(1));
            String unit = m.group(3);
            boolean after = "后".equals(m.group(5));
            if ("半".equals(m.group(1))) {
                n = 1;
            }
            LocalDateTime t = shift(now, n, unit, after);
            if (t != null) {
                return Optional.of(TimeRange.point(t));
            }
        }

        // —— 窗口：最近7天 / 近3个季度 ——
        m = WINDOW.matcher(text);
        if (m.find()) {
            long n = parseNum(m.group(2));
            if ("半".equals(m.group(2))) {
                n = 1;
            }
            String unit = m.group(4);
            LocalDateTime end = now;
            LocalDateTime start = shift(now, n, unit, false);
            if (start != null) {
                return Optional.of(TimeRange.range(start, end));
            }
        }

        // —— 上/这/下 周X ——
        for (Map.Entry<String, DayOfWeek> e : WEEKDAY.entrySet()) {
            for (String prefix : new String[]{"上上", "下下", "上", "下", "这", ""}) {
                String word = prefix + e.getKey();
                if (text.contains(word)) {
                    LocalDate base = LocalDate.now();
                    LocalDate target = base.with(java.time.temporal.TemporalAdjusters.nextOrSame(e.getValue()));
                    int weekShift = prefix.startsWith("上上") ? -2 : prefix.startsWith("下下") ? 2
                            : prefix.contains("上") ? -1 : prefix.contains("下") ? 1 : 0;
                    target = target.plusWeeks(weekShift);
                    return Optional.of(TimeRange.range(target.atStartOfDay(), target.atTime(LocalTime.MAX)));
                }
            }
        }
        return Optional.empty();
    }

    private TimeRange fixed(String text, LocalDateTime now) {
        LocalDate today = now.toLocalDate();
        if (text.contains("昨天")) {
            return TimeRange.range(today.minusDays(1).atStartOfDay(), today.minusDays(1).atTime(LocalTime.MAX));
        }
        if (text.contains("前天")) {
            return TimeRange.range(today.minusDays(2).atStartOfDay(), today.minusDays(2).atTime(LocalTime.MAX));
        }
        if (text.contains("今天") || text.contains("今日")) {
            return TimeRange.range(today.atStartOfDay(), now);
        }
        if (text.contains("明天") || text.contains("明日")) {
            return TimeRange.range(today.plusDays(1).atStartOfDay(), today.plusDays(1).atTime(LocalTime.MAX));
        }
        if (text.contains("明早")) {
            return TimeRange.point(today.plusDays(1).atTime(8, 0));
        }
        if (text.contains("今晚") || text.contains("今夜")) {
            return TimeRange.range(today.atTime(20, 0), today.atTime(LocalTime.MAX));
        }
        if (text.contains("本月") || text.contains("这个月")) {
            return TimeRange.range(today.withDayOfMonth(1).atStartOfDay(),
                    today.withDayOfMonth(1).plusMonths(1).minusDays(1).atTime(LocalTime.MAX));
        }
        if (text.contains("下个月底")) {
            LocalDate eom = today.plusMonths(1).withDayOfMonth(1).plusMonths(1).minusDays(1);
            return TimeRange.point(eom.atTime(23, 59, 59));
        }
        if (text.contains("月底") || text.contains("月末")) {
            return TimeRange.point(today.withDayOfMonth(1).plusMonths(1).minusDays(1).atTime(23, 59, 59));
        }
        if (text.contains("去年同期")) {
            LocalDate lastYear = today.minusYears(1);
            return TimeRange.range(lastYear.minusDays(3).atStartOfDay(), lastYear.plusDays(3).atTime(LocalTime.MAX));
        }
        if (text.contains("去年")) {
            return TimeRange.range(today.minusYears(1).withDayOfYear(1).atStartOfDay(),
                    today.minusYears(1).withDayOfYear(1).plusYears(1).minusDays(1).atTime(LocalTime.MAX));
        }
        if (text.contains("本周") || text.contains("这周") || text.contains("这星期")) {
            LocalDate mon = today.with(DayOfWeek.MONDAY);
            return TimeRange.range(mon.atStartOfDay(), mon.plusDays(6).atTime(LocalTime.MAX));
        }
        if (text.contains("上周") || text.contains("上星期")) {
            LocalDate mon = today.with(DayOfWeek.MONDAY).minusWeeks(1);
            return TimeRange.range(mon.atStartOfDay(), mon.plusDays(6).atTime(LocalTime.MAX));
        }
        if (text.contains("下周") || text.contains("下星期")) {
            LocalDate mon = today.with(DayOfWeek.MONDAY).plusWeeks(1);
            return TimeRange.range(mon.atStartOfDay(), mon.plusDays(6).atTime(LocalTime.MAX));
        }
        return null;
    }

    private LocalDateTime shift(LocalDateTime base, long n, String unit, boolean after) {
        long sign = after ? 1 : -1;
        return switch (unit) {
            case "小时" -> base.plusHours(sign * n);
            case "分钟" -> base.plusMinutes(sign * n);
            case "天", "日" -> base.plusDays(sign * n);
            case "周", "星期" -> base.plusWeeks(sign * n);
            case "个月", "月" -> base.plusMonths(sign * n);
            case "季度" -> base.plusMonths(sign * 3 * n);
            case "年" -> base.plusYears(sign * n);
            default -> null;
        };
    }

    private long parseNum(String s) {
        if (s.matches("\\d+")) {
            return Long.parseLong(s);
        }
        if (CN_NUM.containsKey(s)) {
            return CN_NUM.get(s);
        }
        // 十X / X十 / X十Y
        if (s.contains("十")) {
            String[] parts = s.split("十");
            long tens = parts[0].isEmpty() ? 1 : CN_NUM.getOrDefault(parts[0], 1L);
            long ones = parts.length > 1 ? CN_NUM.getOrDefault(parts[1], 0L) : 0;
            return tens * 10 + ones;
        }
        return 1;
    }
}
