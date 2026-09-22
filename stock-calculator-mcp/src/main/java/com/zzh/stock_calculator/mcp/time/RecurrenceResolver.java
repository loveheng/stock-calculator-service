package com.zzh.stock_calculator.mcp.time;

import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 周期与频次时间解析：固定频次（每天/每周一/每个工作日/每月1号）、间隔循环（每隔2小时/每15分钟一次）、
 * 时段重复（每天开盘前10分钟/每周五收盘后）。
 * 出口为 Spring cron 表达式（6位）+ 一次校准计算的下一次触发时刻；RECURRING 语义，非单次区间。
 */
@Component
public class RecurrenceResolver {

    private static final Pattern INTERVAL = Pattern.compile("每隔?\\s*([一二两三四五六七八九十\\d]+)\\s*(个)?(小时|分钟|天|日|周|星期)");
    private static final Pattern WEEKLY = Pattern.compile("每(?:周|星期)([一二三四五六日天])");
    private static final Pattern MONTHLY = Pattern.compile("每月\\s*(\\d{1,2})[日号]");

    private static final Map<String, Integer> CN = Map.ofEntries(Map.entry("一", 1), Map.entry("两", 2),
            Map.entry("二", 2), Map.entry("三", 3), Map.entry("四", 4), Map.entry("五", 5),
            Map.entry("六", 6), Map.entry("七", 7), Map.entry("八", 8), Map.entry("九", 9),
            Map.entry("十", 10));

    private static final Map<String, String> DAYPART_CRON = Map.of(
            "开盘前", "0 50 9 * * ?",      // 每天 09:50（开盘前10分钟）
            "收盘后", "0 30 15 * * ?");    // 每周五收盘后单独在 weekly 分支处理

    public Optional<TimeRange> resolve(String text) {
        String cron = null;
        String note = null;

        // —— 时段重复：每天开盘前10分钟 / 每周五收盘后 ——
        if (text.contains("每个工作日") || text.contains("每个交易日")) {
            cron = "0 0 9 ? * MON-FRI";
            note = "每个工作日 09:00";
        } else if (text.contains("开盘前")) {
            cron = "0 50 9 * * ?";
            note = "每天开盘前10分钟（09:50）";
        } else if (text.contains("收盘后") && text.contains("每周五")) {
            cron = "0 30 15 ? * FRI";
            note = "每周五收盘后（15:30）";
        } else if (text.contains("收盘后")) {
            cron = "0 30 15 * * ?";
            note = "每天收盘后（15:30）";
        }

        // —— 每月N号 ——
        if (cron == null) {
            Matcher m = MONTHLY.matcher(text);
            if (m.find()) {
                cron = "0 0 0 " + m.group(1) + " * ?";
                note = "每月" + m.group(1) + "日 00:00";
            }
        }

        // —— 每周X ——
        if (cron == null) {
            Matcher m = WEEKLY.matcher(text);
            if (m.find()) {
                DayOfWeek dow = cnDay(m.group(1));
                cron = "0 0 0 ? * " + dow.name().substring(0, 3);
                note = "每周" + m.group(1) + " 00:00";
            }
        }

        // —— 间隔循环：每隔2小时 / 每15分钟 ——
        if (cron == null) {
            Matcher m = INTERVAL.matcher(text);
            if (m.find()) {
                int n = m.group(1).matches("\\d+") ? Integer.parseInt(m.group(1)) : CN.getOrDefault(m.group(1), 1);
                cron = switch (m.group(3)) {
                    case "分钟" -> "0 0/" + n + " * * * ?";
                    case "小时" -> "0 0 0/" + n + " * * ?";
                    case "天", "日" -> "0 0 0 1/" + n + " * ?";
                    case "周", "星期" -> "0 0 0 ? * MON";
                    default -> null;
                };
                note = "每" + n + m.group(3);
                if ("周".equals(m.group(3)) || "星期".equals(m.group(3))) {
                    note = "每" + n + "周（周一）";
                }
            }
        }

        // —— 固定频次兜底 ——
        if (cron == null) {
            if (text.contains("每天") || text.contains("每日")) {
                cron = "0 0 0 * * ?";
                note = "每天 00:00";
            } else if (text.equals("每周一") || text.contains("每周一")) {
                cron = "0 0 0 ? * MON";
                note = "每周一 00:00";
            }
        }

        if (cron == null) {
            return Optional.empty();
        }
        return Optional.of(TimeRange.builder()
                .kind(TimeKind.RECURRING)
                .start(nextFire(cron)).end(nextFire(cron))
                .confirmed(true).cron(cron).note(note).build());
    }

    private DayOfWeek cnDay(String c) {
        return switch (c) {
            case "一" -> DayOfWeek.MONDAY;
            case "二" -> DayOfWeek.TUESDAY;
            case "三" -> DayOfWeek.WEDNESDAY;
            case "四" -> DayOfWeek.THURSDAY;
            case "五" -> DayOfWeek.FRIDAY;
            case "六" -> DayOfWeek.SATURDAY;
            default -> DayOfWeek.SUNDAY;
        };
    }

    /** cron 下一次触发时刻的轻量估算：分钟级扫描未来 366 天，命中即返回（demo 量级够用，调度引擎以 cron 为准） */
    private LocalDateTime nextFire(String cron) {
        org.springframework.scheduling.support.CronExpression expr = org.springframework.scheduling.support.CronExpression.parse(cron);
        return expr.next(LocalDateTime.now());
    }
}
