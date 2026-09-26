package com.zzh.stock_calculator.mcp.tool;

import com.zzh.stock_calculator.mcp.quote.TencentRealtimeClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP 工具：实时行情批量取价（docs/alert/design.md §四.1）。
 * <p>腾讯 qt.gtimg.cn 批量现价，一次 ≤50 只自动分批；仅取价不入库（实时快照无交易日维度）。
 * 主消费方：main 侧价格预告单监控（MonitorCheckTask），经 dispatch 确定性调用；
 * 监控一轮多任务合并为按股票去重后的批量请求，防 IP 封禁。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FetchRealtimeQuoteTool {

    /** 入参股票数上限（监控场景一轮至多几十只 RUNNING 任务，取 200 防滥用） */
    private static final int MAX_CODES = 200;

    private final TencentRealtimeClient realtimeClient;

    @Tool(name = "fetch_realtime_quote",
            description = "批量获取股票实时现价（腾讯行情源，交易时段为最新价，非交易时段为最近收盘价）。"
                    + "入参为股票代码列表（6 位码如 600519 / 带前缀如 sh600519 均可），"
                    + "返回 quotes: {代码: 现价}；停牌/未开盘/解析失败的股票不出现在结果中。")
    public Map<String, Object> fetchRealtimeQuote(
            @ToolParam(description = "股票代码列表，如 [\"600519\",\"sz000001\"]，单次 ≤200 只")
            List<String> codes,
            @ToolParam(required = false, description = "调用方追踪 ID，原样回传") String traceId) {
        List<String> normalized = TencentRealtimeClient.normalize(codes);
        if (normalized.isEmpty()) {
            return Map.of("error", "codes 缺失或全为空");
        }
        if (normalized.size() > MAX_CODES) {
            return Map.of("error", "单次最多 " + MAX_CODES + " 只，实收 " + normalized.size());
        }
        Map<String, BigDecimal> quotes = realtimeClient.fetchRealtime(normalized);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("quotes", quotes);
        out.put("requested", normalized.size());
        out.put("resolved", quotes.size());
        if (traceId != null && !traceId.isBlank()) {
            out.put("traceId", traceId);
        }
        return out;
    }

    @Tool(name = "fetch_m30_range",
            description = "批量获取股票「当轮 30 分钟 K 线」区间 low/high（腾讯分钟线源，盘中为进行中 bar，收盘后为最后一根完整 bar）。"
                    + "入参股票代码列表（单次 ≤200 只），返回 ranges: {代码: [low, high]}；缺 m30 数据的股票不出现在结果中。"
                    + "主消费方：main 价格预告单监控（消盘中触及又回落的漏报）。")
    public Map<String, Object> fetchM30Range(
            @ToolParam(description = "股票代码列表，如 [\"600519\",\"sz000001\"]，单次 ≤200 只")
            List<String> codes,
            @ToolParam(required = false, description = "调用方追踪 ID，原样回传") String traceId) {
        List<String> normalized = TencentRealtimeClient.normalize(codes);
        if (normalized.isEmpty()) {
            return Map.of("error", "codes 缺失或全为空");
        }
        if (normalized.size() > MAX_CODES) {
            return Map.of("error", "单次最多 " + MAX_CODES + " 只，实收 " + normalized.size());
        }
        Map<String, BigDecimal[]> ranges = realtimeClient.fetchM30Ranges(normalized);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ranges", ranges);
        out.put("requested", normalized.size());
        out.put("resolved", ranges.size());
        if (traceId != null && !traceId.isBlank()) {
            out.put("traceId", traceId);
        }
        return out;
    }
}
