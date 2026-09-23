package com.zzh.stock_calculator.mcp.tool;

import com.zzh.stock_calculator.mcp.dict.StockDictEntry;
import com.zzh.stock_calculator.mcp.dict.StockDictMemoryService;
import com.zzh.stock_calculator.mcp.quote.DailyBar;
import com.zzh.stock_calculator.mcp.quote.QuoteSyncService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * MCP 工具：多条件雷达断言（orchestration 3① 条件判定前置）。
 * 基于日线算出 MA20 关系与量能倍数，输出**布尔/枚举断言值**——DAG 的 switch 节点
 * 只做枚举路由不做表达式求值（§八底线），复合条件逻辑（AND/OR）在工具侧收敛成
 * 单个信号枚举（both/break_only/volume_only/none），switch 直接按枚举分流。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StockRadarCheckTool {

    private final StockDictMemoryService dictService;
    private final QuoteSyncService quoteSyncService;

    @Tool(name = "stock_radar_check", description = "多条件雷达断言：检查个股是否『突破N日均线』和/或『成交量放大』，"
            + "返回枚举信号 both/break_only/volume_only/none（供编排 switch 节点分流，命中才触发提醒）。"
            + "均线窗口与量能倍数均可自定义；数据不足（日线少于均线窗口+5）返回信号 none 并带 data_insufficient 标记。")
    public Map<String, Object> radarCheck(
            @ToolParam(description = "股票代码或名称，如 600519 / sh600519 / 宁德时代") String stock,
            @ToolParam(required = false, description = "均线窗口（日），默认 20，范围 5-120") Integer maWindow,
            @ToolParam(required = false, description = "量能放大倍数阈值，默认 2.0（当日量 / 前5日均量）") Double volumeRatio) {
        Optional<StockDictEntry> entry = dictService.resolve(stock);
        if (entry.isEmpty()) {
            return Map.of("signal", "none", "error", "无法解析股票: " + stock);
        }
        // 参数边界钳制（系统边界校验）：窗口 5-120（过小噪声大、过大无意义），倍数 (0, 100]
        int window = maWindow == null ? 20 : Math.min(Math.max(maWindow, 5), 120);
        double ratioThreshold = volumeRatio == null || volumeRatio <= 0 ? 2.0 : Math.min(volumeRatio, 100);
        String stockId = entry.get().getStockId();
        // 均线窗口 N 根 + 前5日均量再要 5 根，取 N+10 根余量
        List<DailyBar> bars = quoteSyncService.ensureBars(stockId, window + 10);
        if (bars.size() < window + 1) {
            return Map.of("signal", "none", "dataInsufficient", true,
                    "stockId", stockId, "bars", bars.size());
        }
        DailyBar last = bars.get(bars.size() - 1);
        double ma = bars.subList(bars.size() - window - 1, bars.size() - 1).stream()
                .mapToDouble(DailyBar::getClose).average().orElse(0);
        boolean brokeAbove = last.getClose() > ma && bars.get(bars.size() - 2).getClose() <= ma;
        double prev5AvgVolume = bars.subList(bars.size() - 6, bars.size() - 1).stream()
                .mapToDouble(DailyBar::getVolume).average().orElse(0);
        double volumeMultiple = prev5AvgVolume > 0 ? last.getVolume() / prev5AvgVolume : 0;
        boolean volumeSurged = volumeMultiple >= ratioThreshold;

        String signal = brokeAbove && volumeSurged ? "both"
                : brokeAbove ? "break_only"
                : volumeSurged ? "volume_only" : "none";

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("stockId", stockId);
        out.put("name", entry.get().getName());
        out.put("signal", signal);
        out.put("breakAboveMa", brokeAbove);
        out.put("maWindow", window);
        out.put("volumeMultiple", round2(volumeMultiple));
        out.put("volumeRatioThreshold", ratioThreshold);
        out.put("ma", round2(ma));
        out.put("lastClose", round2(last.getClose()));
        return out;
    }

    private double round2(double v) {
        return Math.round(v * 100) / 100.0;
    }
}
