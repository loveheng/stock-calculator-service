package com.zzh.stock_calculator.mcp.tool;

import com.zzh.stock_calculator.mcp.dict.StockDictEntry;
import com.zzh.stock_calculator.mcp.dict.StockDictMemoryService;
import com.zzh.stock_calculator.mcp.indicator.AnalysisResult;
import com.zzh.stock_calculator.mcp.indicator.IndicatorService;
import com.zzh.stock_calculator.mcp.quote.DailyBar;
import com.zzh.stock_calculator.mcp.quote.QuoteFetchException;
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
 * MCP 工具：个股技术指标全家桶（最新值 + 信号，不吐序列）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StockAnalysisTool {

    private final StockDictMemoryService dictService;
    private final QuoteSyncService quoteSyncService;
    private final IndicatorService indicatorService;

    @Tool(name = "stock_analysis", description = "A股个股技术指标全家桶：MA5/10/20/60、MACD(12,26,9)、RSI14、"
            + "BOLL(20,2)、KDJ(9,3,3) 的最新值与趋势信号（均线排列/金叉死叉/超买超卖）。"
            + "入参支持 sh/sz 前缀代码、6 位代码或股票名称模糊解析（唯一命中才返回）。基于日线（前复权）。")
    public Map<String, Object> analysis(
            @ToolParam(description = "股票代码或名称，如 600519 / sh600519 / 贵州茅台") String stock,
            @ToolParam(required = false, description = "回看日线根数，默认 250，范围 30-500") Integer days) {
        Optional<StockDictEntry> entry = dictService.resolve(stock);
        if (entry.isEmpty()) {
            return Map.of("error", "无法解析股票: " + stock
                    + "（请提供 6 位代码 / sh/sz 前缀代码 / 精确或唯一模糊的名称）", "dictSize", dictService.size());
        }
        int n = days == null ? 250 : Math.min(Math.max(days, 30), 500);
        String stockId = entry.get().getStockId();
        try {
            List<DailyBar> bars = quoteSyncService.ensureBars(stockId, n);
            AnalysisResult result = indicatorService.analyze(bars);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("stockId", stockId);
            out.put("name", entry.get().getName());
            out.put("barsUsed", bars.size());
            out.put("lastDate", result.getLastDate());
            out.put("lastClose", result.getLastClose());
            out.put("changePct", result.getChangePct());
            out.put("ma", result.getMa());
            out.put("macd", result.getMacd());
            out.put("rsi14", result.getRsi());
            out.put("boll", result.getBoll());
            out.put("kdj", result.getKdj());
            out.put("signals", result.getSignals());
            return out;
        } catch (QuoteFetchException e) {
            log.warn("analysis 拉取失败 stockId={}: {}", stockId, e.getMessage());
            return Map.of("error", e.getMessage(), "stockId", stockId);
        }
    }
}
