package com.zzh.stock_calculator.mcp.time;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 持续时长解析：『持续30分钟 / 横盘整整3个月 / 挂单有效期7天』→ 归一为 ISO-8601 duration 字符串（PT30M / P3M / P7D）。
 * 时长不是时刻，因此输出放在 note 里以 ISO-8601 表达，start/end 置空。
 */
@Component
public class DurationResolver {

    private static final Pattern DURATION = Pattern.compile(
            "(?:持续|有效期|整整|历时|时长)?\\s*([一二两三四五六七八九十百半\\d]+)\\s*(个)?(小时|分钟|秒|天|日|周|星期|个月|月|季度|年)");

    private static final Map<String, Integer> CN = Map.ofEntries(Map.entry("一", 1), Map.entry("两", 2),
            Map.entry("二", 2), Map.entry("三", 3), Map.entry("四", 4), Map.entry("五", 5),
            Map.entry("六", 6), Map.entry("七", 7), Map.entry("八", 8), Map.entry("九", 9),
            Map.entry("十", 10), Map.entry("半", 1), Map.entry("十五", 15), Map.entry("三十", 30));

    public Optional<TimeRange> resolve(String text) {
        Matcher m = DURATION.matcher(text);
        if (!m.find()) {
            return Optional.empty();
        }
        long n = m.group(1).matches("\\d+") ? Long.parseLong(m.group(1)) : CN.getOrDefault(m.group(1), 1);
        if ("半".equals(m.group(1))) {
            n = 1;
        }
        String unit = m.group(3);
        String iso = switch (unit) {
            case "秒" -> "PT" + n + "S";
            case "分钟" -> "PT" + n + "M";
            case "小时" -> "PT" + n + "H";
            case "天", "日" -> "P" + n + "D";
            case "周", "星期" -> "P" + (n * 7) + "D";
            case "个月", "月" -> "P" + n + "M";
            case "季度" -> "P" + (n * 3) + "M";
            case "年" -> "P" + n + "Y";
            default -> null;
        };
        if (iso == null) {
            return Optional.empty();
        }
        return Optional.of(TimeRange.builder()
                .kind(TimeKind.RANGE)
                .confirmed(true)
                .note("持续时长：" + n + unit + "（ISO-8601: " + iso + "）")
                .build());
    }
}
