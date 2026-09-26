package com.zzh.stock_calculator.mcp.tool;

import com.zzh.stock_calculator.mcp.indicator.RadarCheckService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * MCP 工具：批量雷达过筛（选股引导候选清单 / 监控股票池的一轮过筛）。
 * 对一组候选股逐只跑 stock_radar_check 同款条件断言，输出逐只命中摘要 + 汇总命中数，
 * LLM 一轮调用替代 N 轮单股调用。断言逻辑与单股完全同源（RadarCheckService）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StockRadarBatchTool {

    /** 批量上限：引导候选/监控池一轮量级，取 50 防滥用 */
    private static final int MAX_STOCKS = 50;

    private final RadarCheckService radarCheckService;

    @Tool(name = "stock_radar_batch", description = "批量雷达过筛：对一组候选股票逐只跑 stock_radar_check 同款条件断言，"
            + "返回逐只命中摘要（matched/allMatched/signal/changePct/lastClose）与汇总 hitCount，"
            + "适合候选清单过筛（选股引导 Step1 候选、自选池巡检）。"
            + "conditions 语义与 stock_radar_check 完全一致（macd_golden/volume_surge/pct_up 等 12 种），"
            + "缺省只查均线突破+量能放大；单次 ≤50 只，无法解析的代码进 unresolved 不中断整批。")
    public Map<String, Object> radarBatch(
            @ToolParam(description = "股票代码或名称列表，如 [\"600519\",\"sz000001\",\"宁德时代\"]，单次 ≤50 只") List<String> stocks,
            @ToolParam(required = false, description = "条件列表，语义同 stock_radar_check；缺省只查均线突破+量能") List<String> conditions,
            @ToolParam(required = false, description = "均线窗口（日），默认 20，范围 5-120") Integer maWindow,
            @ToolParam(required = false, description = "量能放大倍数阈值，默认 2.0") Double volumeRatio,
            @ToolParam(required = false, description = "涨跌幅阈值（%），默认 3，仅 pct_up/pct_down 使用") Double pctThreshold) {
        if (stocks == null || stocks.isEmpty()) {
            return Map.of("error", "stocks 缺失或全为空");
        }
        List<String> distinct = new ArrayList<>(new LinkedHashSet<>(stocks.stream()
                .filter(s -> s != null && !s.isBlank()).map(String::trim).toList()));
        if (distinct.isEmpty()) {
            return Map.of("error", "stocks 缺失或全为空");
        }
        if (distinct.size() > MAX_STOCKS) {
            return Map.of("error", "单次最多 " + MAX_STOCKS + " 只，实收 " + distinct.size());
        }
        List<Map<String, Object>> results = new ArrayList<>();
        List<Map<String, Object>> unresolved = new ArrayList<>();
        int hitCount = 0;
        for (String stock : distinct) {
            Map<String, Object> out = radarCheckService.check(stock, maWindow, volumeRatio, conditions, pctThreshold);
            if (out.containsKey("error")) {
                unresolved.add(Map.of("stock", stock, "reason", out.get("error")));
                continue;
            }
            if (Boolean.TRUE.equals(out.get("dataInsufficient"))) {
                unresolved.add(Map.of("stock", stock,
                        "reason", "数据不足（日线 " + out.get("bars") + " 根 < 所需条件最小根数）"));
                continue;
            }
            boolean allMatched = Boolean.TRUE.equals(out.get("allMatched"));
            if (allMatched) {
                hitCount++;
            }
            Map<String, Object> compact = new LinkedHashMap<>();
            compact.put("stockId", out.get("stockId"));
            compact.put("name", out.get("name"));
            compact.put("signal", out.get("signal"));
            compact.put("matched", out.get("matched"));
            compact.put("allMatched", allMatched);
            compact.put("changePct", out.get("changePct"));
            compact.put("lastClose", out.get("lastClose"));
            results.add(compact);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("requested", distinct.size());
        out.put("resolved", results.size());
        out.put("hitCount", hitCount);
        out.put("results", results);
        out.put("unresolved", unresolved);
        return out;
    }
}
