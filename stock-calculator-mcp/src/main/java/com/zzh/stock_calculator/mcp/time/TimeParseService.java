package com.zzh.stock_calculator.mcp.time;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * 时间语义解析门面：按「绝对 → 交易日 → 相对 → 周期 → 模糊 → 事件 → 时长」顺序尝试各 resolver，
 * 首个命中即返回。顺序原则：确定性高的先命中（绝对/交易日优先于泛化的相对与模糊表达）。
 */
@Service
@RequiredArgsConstructor
public class TimeParseService {

    private final AbsoluteTimeResolver absolute;
    private final TradingTimeResolver trading;
    private final RelativeTimeResolver relative;
    private final RecurrenceResolver recurrence;
    private final FuzzyTimeResolver fuzzy;
    private final EventTimeResolver event;
    private final DurationResolver duration;

    public TimeRange parse(String text) {
        if (text == null || text.isBlank()) {
            return unresolved(text);
        }
        Optional<TimeRange> r;
        if ((r = absolute.resolve(text)).isPresent()) return r.get();
        if ((r = trading.resolve(text)).isPresent()) return r.get();
        if ((r = relative.resolve(text)).isPresent()) return r.get();
        if ((r = recurrence.resolve(text)).isPresent()) return r.get();
        if ((r = fuzzy.resolve(text)).isPresent()) return r.get();
        if ((r = event.resolve(text)).isPresent()) return r.get();
        if ((r = duration.resolve(text)).isPresent()) return r.get();
        return unresolved(text);
    }

    private TimeRange unresolved(String text) {
        return TimeRange.builder()
                .kind(TimeKind.NEED_CONFIRM).confirmed(false)
                .note("无法识别时间语义：" + text + "，请给出具体时间或范围")
                .build();
    }
}
