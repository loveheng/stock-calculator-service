package com.zzh.stock_calculator.mcp.tool;

import com.zzh.stock_calculator.mcp.indicator.IndicatorSeriesService;
import com.zzh.stock_calculator.mcp.quote.DailyBar;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP 工具：画布无状态复杂指标计算（free-canvas §3.1，路径 A——前端喂切片算完即弃）。
 * <p>工具分级纪律（硬约束 #6）：纯计算 + 本域只读，禁外部 IO——输入切片由调用方携带，
 * 不触库不触行情出口，8s ToolInvoker 预算内秒回。数组与输入 klines 一一对齐、暖机 null 占位。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ComputeIndicatorsTool {

    /** 防御性上限（契约前端切片 ≤120 根；工具层放宽到 500 兜 LLM 长窗口） */
    private static final int MAX_BARS = 500;

    private final IndicatorSeriesService indicatorSeriesService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Tool(name = "compute_indicators", description = "对给定 K 线切片逐根计算复杂指标序列（macd/kdj/boll），"
            + "数组与输入一一对齐，暖机期 null 占位，根数不足 minBars 整体 null。纯计算无外部 IO。")
    public Map<String, Object> computeIndicators(
            @ToolParam(description = "K 线切片 JSON 数组字符串，元素 {date,open,close,high,low,volume}，升序 ≤120 根") String klines,
            @ToolParam(description = "指标名 JSON 数组字符串，如 [\"macd\",\"kdj\",\"boll\"]（白名单）") String indicators) {
        List<DailyBar> bars = parseKlines(klines);
        if (bars == null) {
            return Map.of("error", "klines 解析失败（需 JSON 数组）");
        }
        if (bars.isEmpty()) {
            return Map.of("error", "klines 为空");
        }
        if (bars.size() > MAX_BARS) {
            return Map.of("error", "klines 超上限（≤" + MAX_BARS + " 根）: " + bars.size());
        }
        for (int i = 1; i < bars.size(); i++) {
            if (!bars.get(i - 1).getDate().isBefore(bars.get(i).getDate())) {
                return Map.of("error", "klines 非升序: " + bars.get(i - 1).getDate() + " -> " + bars.get(i).getDate());
            }
        }
        List<String> names = parseIndicators(indicators);
        if (names == null) {
            return Map.of("error", "indicators 解析失败（需 JSON 字符串数组）");
        }
        if (names.isEmpty()) {
            return Map.of("error", "indicators 为空");
        }
        for (String name : names) {
            if (!IndicatorSeriesService.WHITELIST.contains(name.toLowerCase())) {
                return Map.of("error", "白名单外指标: " + name);
            }
        }
        try {
            Map<String, Object> result = indicatorSeriesService.compute(bars, names);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("count", bars.size());
            out.put("firstDate", bars.get(0).getDate().toString());
            out.put("lastDate", bars.get(bars.size() - 1).getDate().toString());
            out.put("indicators", result);
            return out;
        } catch (IllegalArgumentException e) {
            return Map.of("error", e.getMessage());
        } catch (RuntimeException e) {
            log.warn("compute_indicators 计算失败: {}", e.getMessage());
            return Map.of("error", "指标计算失败: " + e.getMessage());
        }
    }

    /** 解析切片；非法返回 null（空数组返回空 list） */
    private List<DailyBar> parseKlines(String klines) {
        if (klines == null || klines.isBlank()) {
            return null;
        }
        try {
            JsonNode arr = objectMapper.readTree(klines);
            if (!arr.isArray()) {
                return null;
            }
            List<DailyBar> bars = new ArrayList<>();
            for (JsonNode n : arr) {
                bars.add(DailyBar.builder()
                        .date(LocalDate.parse(n.path("date").asString()))
                        .open(n.path("open").asDouble())
                        .close(n.path("close").asDouble())
                        .high(n.path("high").asDouble())
                        .low(n.path("low").asDouble())
                        .volume(n.path("volume").asDouble())
                        .build());
            }
            return bars;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private List<String> parseIndicators(String indicators) {
        if (indicators == null || indicators.isBlank()) {
            return null;
        }
        try {
            JsonNode arr = objectMapper.readTree(indicators);
            if (!arr.isArray()) {
                return null;
            }
            List<String> names = new ArrayList<>();
            for (JsonNode n : arr) {
                names.add(n.asString().trim().toLowerCase());
            }
            return names;
        } catch (RuntimeException e) {
            return null;
        }
    }
}
