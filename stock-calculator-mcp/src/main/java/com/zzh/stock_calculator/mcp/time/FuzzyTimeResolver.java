package com.zzh.stock_calculator.mcp.time;

import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 模糊与范围时间解析：泛指时段（近期/这段时间）、模糊日内时段（一大早/傍晚/深夜）、模糊跨度（年初至今/月末那几天）。
 * 策略分两级：
 * 1) 带行业默认假设的（日内时段、YTD）：直接产出区间，confirmed=true，note 里写明假设；
 * 2) 语义空间交叠无法定值的（近期/这段时间）：产出候选区间，confirmed=false 交人工确认。
 */
@Component
public class FuzzyTimeResolver {

    private static final LocalDateTime OPEN_END = LocalDateTime.of(9999, 12, 31, 23, 59, 59);

    public Optional<TimeRange> resolve(String text) {
        LocalDateTime now = LocalDateTime.now();

        // —— YTD 类：边界确定 ——
        if (text.contains("年初至今") || text.contains("今年以来") || text.contains("YTD")) {
            LocalDate yearStart = now.toLocalDate().withDayOfYear(1);
            return Optional.of(TimeRange.range(yearStart.atStartOfDay(), now)
                    .toBuilder().note("YTD：当年1月1日至今").build());
        }
        if (text.contains("本周以来")) {
            LocalDate mon = now.toLocalDate().with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY));
            return Optional.of(TimeRange.range(mon.atStartOfDay(), now));
        }
        if (text.contains("月末那几天")) {
            LocalDate eom = now.toLocalDate().withDayOfMonth(1).plusMonths(1).minusDays(1);
            return Optional.of(TimeRange.range(eom.minusDays(4).atStartOfDay(), eom.atTime(LocalTime.MAX))
                    .toBuilder().note("月末那几天按最后5天").build());
        }

        // —— 模糊日内时段：有默认假设，confirmed=true ——
        TimeRange daypart = daypart(text, now.toLocalDate());
        if (daypart != null) {
            return Optional.of(daypart);
        }

        // —— 泛指时段：无法定值，返回候选交确认 ——
        List<TimeRange> candidates = vagueCandidates(text, now);
        if (!candidates.isEmpty()) {
            return Optional.of(TimeRange.builder().kind(TimeKind.NEED_CONFIRM)
                    .confirmed(false).candidates(candidates)
                    .note("表达过于模糊，请确认具体范围").build());
        }
        return Optional.empty();
    }

    private TimeRange daypart(String text, LocalDate day) {
        if (text.contains("一大早") || text.contains("清晨")) {
            return withNote(TimeRange.range(day.atTime(5, 0), day.atTime(7, 0)), "一大早按 05:00-07:00");
        }
        if (text.contains("早上") || text.contains("早晨")) {
            return withNote(TimeRange.range(day.atTime(7, 0), day.atTime(9, 0)), "早上按 07:00-09:00");
        }
        if (text.contains("上午")) {
            return withNote(TimeRange.range(day.atTime(9, 0), day.atTime(12, 0)), "上午按 09:00-12:00");
        }
        if (text.contains("中午")) {
            return withNote(TimeRange.range(day.atTime(11, 30), day.atTime(13, 30)), "中午按 11:30-13:30");
        }
        if (text.contains("午后") || text.contains("下午")) {
            return withNote(TimeRange.range(day.atTime(13, 0), day.atTime(17, 0)), "午后按 13:00-17:00");
        }
        if (text.contains("傍晚")) {
            return withNote(TimeRange.range(day.atTime(17, 0), day.atTime(19, 0)), "傍晚按 17:00-19:00");
        }
        if (text.contains("晚上")) {
            return withNote(TimeRange.range(day.atTime(19, 0), day.atTime(23, 0)), "晚上按 19:00-23:00");
        }
        if (text.contains("深夜") || text.contains("凌晨")) {
            return withNote(TimeRange.range(day.atTime(23, 0).minusDays(1).plusDays(1), day.atTime(5, 0))
                            .toBuilder().start(day.atTime(0, 0)).end(day.atTime(5, 0)).build(),
                    "深夜按 00:00-05:00");
        }
        return null;
    }

    private List<TimeRange> vagueCandidates(String text, LocalDateTime now) {
        List<TimeRange> list = new ArrayList<>();
        if (text.contains("近期") || text.contains("最近") && !text.matches(".*最近\\s*[\\d一二两三四十]+.*")
                || text.contains("这段时间") || text.contains("这阵子") || text.contains("这阵")) {
            list.add(withNote(TimeRange.range(now.minusDays(7), now), "候选1：最近一周"));
            list.add(withNote(TimeRange.range(now.minusDays(30), now), "候选2：最近一个月"));
            list.add(withNote(TimeRange.range(now.minusDays(90), now), "候选3：最近一个季度"));
        }
        return list;
    }

    private TimeRange withNote(TimeRange r, String note) {
        return r.toBuilder().note(note).build();
    }
}
