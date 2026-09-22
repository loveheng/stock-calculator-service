package com.zzh.stock_calculator.mcp.time;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 事件触发时间解析：时间不是固定值，而是「某事件后的相对延时」（适合 MQ 延时队列/死信场景）。
 * 只结构化 {锚点事件, 延时量, 延时单位}，事件本身的语义解析不属于本工具职责。
 * 例：『当股价突破200元后的第3天』→ anchor="股价突破200元", delay=3天；『买入操作完成后24小时』→ delay=24小时。
 */
@Component
public class EventTimeResolver {

    private static final Pattern AFTER_EVENT = Pattern.compile(
            "(?:当|一旦|如果)?(.{1,30}?)(?:之后|以后|后)(?:的)?第\\s*([一二两三四五六七八九十\\d]+)\\s*(个)?(天|日|小时|分钟|周|月)");
    private static final Pattern AFTER_DONE = Pattern.compile(
            "(.{1,30}?)(?:完成后|操作完成后|结束后|执行完后)\\s*([一二两三四五六七八九十百半\\d]+)\\s*(个)?(小时|分钟|天|日|周|月)");

    private static final Map<String, Integer> CN = Map.ofEntries(Map.entry("一", 1), Map.entry("两", 2),
            Map.entry("二", 2), Map.entry("三", 3), Map.entry("四", 4), Map.entry("五", 5),
            Map.entry("六", 6), Map.entry("七", 7), Map.entry("八", 8), Map.entry("九", 9),
            Map.entry("十", 10), Map.entry("半", 1));

    public Optional<TimeRange> resolve(String text) {
        String anchor = null;
        long delay;
        String unit;

        Matcher m = AFTER_EVENT.matcher(text);
        if (m.find()) {
            anchor = m.group(1).trim();
            delay = cnNum(m.group(2));
            unit = m.group(4);
        } else {
            m = AFTER_DONE.matcher(text);
            if (!m.find()) {
                return Optional.empty();
            }
            anchor = m.group(1).trim();
            delay = cnNum(m.group(2));
            unit = m.group(4);
        }

        return Optional.of(TimeRange.builder()
                .kind(TimeKind.NEED_CONFIRM)   // 事件时间依赖事件真实发生时刻，无法静态定值
                .confirmed(false)
                .note("事件延时：" + anchor + " 后 " + delay + unit + "（事件发生时刻待定，配合 MQ 延时队列使用）")
                .build());
    }

    private long cnNum(String s) {
        if (s.matches("\\d+")) {
            return Long.parseLong(s);
        }
        if (CN.containsKey(s)) {
            return CN.get(s);
        }
        if (s.contains("十")) {
            String[] parts = s.split("十");
            long tens = parts[0].isEmpty() ? 1 : CN.getOrDefault(parts[0], 1);
            long ones = parts.length > 1 ? CN.getOrDefault(parts[1], 0) : 0;
            return tens * 10 + ones;
        }
        return 1;
    }
}
