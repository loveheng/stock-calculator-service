package com.zzh.stock_calculator.mcp.tool;

import com.zzh.stock_calculator.mcp.dict.StockDictEntry;
import com.zzh.stock_calculator.mcp.dict.StockDictMemoryService;
import com.zzh.stock_calculator.mcp.indicator.LevelsResult;
import com.zzh.stock_calculator.mcp.indicator.SupportResistanceService;
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
 * MCP 工具：支撑/压力位带（枢轴点 + 摆动点聚类 + 近似成交量密集区三法合一）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StockLevelsTool {

    private final StockDictMemoryService dictService;
    private final QuoteSyncService quoteSyncService;
    private final SupportResistanceService levelsService;

    @Tool(name = "stock_levels", description = "A股个股支撑位与压力位计算（日线级位带，非精确点位）。"
            + "三法合一：昨根枢轴点（短线参考）、摆动高低点聚类（触及次数越多越强）、"
            + "近似成交量密集区（堆积量占比越大越强）。输出由近及远各最多 5 档，带类型与依据。"
            + "入参支持 sh/sz 前缀代码、6 位代码或名称模糊解析。")
    public Map<String, Object> levels(
            @ToolParam(description = "股票代码或名称，如 600519 / sh600519 / 贵州茅台") String stock) {
        Optional<StockDictEntry> entry = dictService.resolve(stock);
        if (entry.isEmpty()) {
            return Map.of("error", "无法解析股票: " + stock, "dictSize", dictService.size());
        }
        String stockId = entry.get().getStockId();
        try {
            List<DailyBar> bars = quoteSyncService.ensureBars(stockId, 250);
            LevelsResult result = levelsService.compute(bars);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("stockId", stockId);
            out.put("name", entry.get().getName());
            out.put("lastDate", result.getLastDate());
            out.put("lastClose", result.getLastClose());
            out.put("resistances", result.getResistances());
            out.put("supports", result.getSupports());
            out.put("note", "日线级位带非精确点位；位带中心高于现价为压力、低于为支撑，穿越后角色互换；配合趋势背景使用");
            return out;
        } catch (QuoteFetchException e) {
            log.warn("levels 拉取失败 stockId={}: {}", stockId, e.getMessage());
            return Map.of("error", e.getMessage(), "stockId", stockId);
        }
    }
}
