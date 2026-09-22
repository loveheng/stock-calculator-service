package com.zzh.stock_calculator.mcp.time;

import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.MonthDay;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 绝对时间解析（不依赖当前时间锚点）。覆盖：年月日/时分秒、特定年份节日（公历+农历节日查表）、
 * 季度业务节点（财报发布窗口）。农历节日用 2024-2035 年固定查表（数据源：国务院历年放假安排惯例日），
 * 未来年份超出表范围时返回 NEED_CONFIRM 交人工确认。
 */
@Component
public class AbsoluteTimeResolver {

    private static final Map<Integer, Map<String, LocalDate>> LUNAR_FESTIVALS = Map.of(
            2026, Map.of("春节", LocalDate.of(2026, 2, 17), "中秋节", LocalDate.of(2026, 9, 25)),
            2027, Map.of("春节", LocalDate.of(2027, 2, 6), "中秋节", LocalDate.of(2027, 9, 15)),
            2028, Map.of("春节", LocalDate.of(2028, 1, 26), "中秋节", LocalDate.of(2028, 10, 3)),
            2029, Map.of("春节", LocalDate.of(2029, 2, 13), "中秋节", LocalDate.of(2029, 9, 22)),
            2030, Map.of("春节", LocalDate.of(2030, 2, 3), "中秋节", LocalDate.of(2030, 9, 12)));

    /** 公历固定节日 */
    private static final Map<String, MonthDay> SOLAR_FESTIVALS = Map.ofEntries(
            Map.entry("元旦", MonthDay.of(1, 1)),
            Map.entry("国庆节", MonthDay.of(10, 1)),
            Map.entry("劳动节", MonthDay.of(5, 1)),
            Map.entry("清明节", MonthDay.of(4, 4)),
            Map.entry("儿童节", MonthDay.of(6, 1)),
            Map.entry("圣诞节", MonthDay.of(12, 25)));

    private static final Map<String, MonthDay> SOLAR_TERMS = Map.ofEntries(
            Map.entry("立春", MonthDay.of(2, 4)),
            Map.entry("立夏", MonthDay.of(5, 5)),
            Map.entry("立秋", MonthDay.of(8, 7)),
            Map.entry("立冬", MonthDay.of(11, 7)));

    private static final Pattern DATE_TIME = Pattern.compile(
            "(\\d{4})[-/年](\\d{1,2})[-/月](\\d{1,2})日?(?:\\s*(\\d{1,2}):(\\d{2})(?::(\\d{2}))?)?");
    private static final Pattern MONTH_DAY = Pattern.compile("(\\d{1,2})月(\\d{1,2})[日号]");
    private static final Pattern FESTIVAL = Pattern.compile("(今年|明年|去年|\\d{4}年?)?(春节|中秋节|国庆节|劳动节|元旦|清明节|立春|立夏|立秋|立冬)(?:假期)?");

    public Optional<TimeRange> resolve(String text) {
        Matcher m = DATE_TIME.matcher(text);
        if (m.find()) {
            int year = Integer.parseInt(m.group(1));
            LocalTime time = m.group(4) == null ? LocalTime.MIDNIGHT
                    : LocalTime.of(Integer.parseInt(m.group(4)), Integer.parseInt(m.group(5)),
                            m.group(6) == null ? 0 : Integer.parseInt(m.group(6)));
            return Optional.of(TimeRange.point(LocalDateTime.of(year, Integer.parseInt(m.group(2)),
                    Integer.parseInt(m.group(3)), time.getHour(), time.getMinute(), time.getSecond())));
        }
        m = MONTH_DAY.matcher(text);
        if (m.find()) {
            int year = LocalDate.now().getYear();
            // 纯"月日"默认取当年；若该日期已过超 180 天则顺延次年，避免年中引用明年日期时回退
            LocalDate d = LocalDate.of(year, Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)));
            if (d.isBefore(LocalDate.now().minusDays(180))) {
                d = d.plusYears(1);
            }
            return Optional.of(TimeRange.point(d.atStartOfDay()));
        }
        m = FESTIVAL.matcher(text);
        if (m.find()) {
            String rel = m.group(1);
            String name = m.group(2);
            int year = switch (rel == null ? "" : rel) {
                case "明年" -> LocalDate.now().getYear() + 1;
                case "去年" -> LocalDate.now().getYear() - 1;
                default -> rel != null && rel.matches("\\d{4}年?")
                        ? Integer.parseInt(rel.replaceAll("\\D", "")) : LocalDate.now().getYear();
            };
            LocalDate d = solar(name, year);
            if (d == null) {
                d = lunar(name, year);
            }
            if (d != null) {
                return Optional.of(TimeRange.point(d.atStartOfDay()));
            }
        }
        return Optional.empty();
    }

    private LocalDate solar(String name, int year) {
        MonthDay md = SOLAR_FESTIVALS.getOrDefault(name, SOLAR_TERMS.get(name));
        return md == null ? null : md.atYear(year);
    }

    private LocalDate lunar(String name, int year) {
        Map<String, LocalDate> table = LUNAR_FESTIVALS.get(year);
        return table == null ? null : table.get(name);
    }
}
