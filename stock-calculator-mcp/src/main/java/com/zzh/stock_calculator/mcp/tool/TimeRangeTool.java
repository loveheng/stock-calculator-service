package com.zzh.stock_calculator.mcp.tool;

import com.zzh.stock_calculator.mcp.time.TimeParseService;
import com.zzh.stock_calculator.mcp.time.TimeRange;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * MCP 工具：自然语言时间 → 确定性时间点/区间（LLM 不自行推算时间，一律经本工具归一）。
 * NEED_CONFIRM 结果必须交用户确认范围，不得擅自采用。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TimeRangeTool {

    private final TimeParseService timeParseService;

    @Tool(name = "time_parse", description = "把中文时间表达解析为具体时间点或时间范围（ Asia/Shanghai）。"
            + "支持：绝对时间（2026年9月21日15:00）、节日/节气、相对时间（昨天/上周五/3小时后/最近7天）、"
            + "交易日语义（上一个交易日/前20个交易日/盘前/盘后/T+1）、周期频次（每天/每周一/每15分钟→cron）、"
            + "模糊时段（傍晚/YTD；过于模糊时返回候选，需转交用户确认）、事件延时（事件后第3天）、时长（持续3个月→ISO-8601）。")
    public Map<String, Object> parse(
            @ToolParam(description = "中文时间表达，如 '上周五收盘后' / '最近7天' / '2026年一季报发布日'") String text) {
        TimeRange r = timeParseService.parse(text);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("text", text);
        out.put("kind", r.getKind() == null ? null : r.getKind().name());
        out.put("start", r.getStart() == null ? null : r.getStart().toString());
        out.put("end", r.getEnd() == null ? null : r.getEnd().toString());
        out.put("confirmed", r.isConfirmed());
        out.put("cron", r.getCron());
        out.put("note", r.getNote());
        if (r.getCandidates() != null && !r.getCandidates().isEmpty()) {
            out.put("candidates", r.getCandidates().stream()
                    .map(c -> Map.of("start", c.getStart() == null ? "" : c.getStart().toString(),
                            "end", c.getEnd() == null ? "" : c.getEnd().toString(),
                            "note", c.getNote() == null ? "" : c.getNote()))
                    .toList());
            out.put("needUserConfirm", true);
        }
        return out;
    }
}
