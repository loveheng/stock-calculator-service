package com.zzh.stock_calculator.mcp.time;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 时间语义归一结果：一切解析出口统一为 TimeRange（时间点 = 起止相同）。
 * confirmed=false 时调用方必须把 candidates 呈现给用户确认，不得擅自取 candidates[0]。
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class TimeRange {
    private TimeKind kind;
    /** 区间起点（POINT 时等于 end） */
    private LocalDateTime start;
    /** 区间终点（POINT 时等于 start；开放终点用 9999-12-31T23:59:59 表示） */
    private LocalDateTime end;
    /** 是否已确定性解析（NEED_CONFIRM 恒为 false） */
    private boolean confirmed;
    /** NEED_CONFIRM 时的候选区间 */
    private List<TimeRange> candidates;
    /** 解析过程中采纳的假设说明（如"傍晚按 17:00-19:00"） */
    private String note;
    /** RECURRING 时的 cron 表达式（6 位 Spring cron），非周期为 null */
    private String cron;

    public static TimeRange point(LocalDateTime t) {
        return TimeRange.builder().kind(TimeKind.POINT).start(t).end(t).confirmed(true).build();
    }

    public static TimeRange range(LocalDateTime s, LocalDateTime e) {
        return TimeRange.builder().kind(TimeKind.RANGE).start(s).end(e).confirmed(true).build();
    }
}
