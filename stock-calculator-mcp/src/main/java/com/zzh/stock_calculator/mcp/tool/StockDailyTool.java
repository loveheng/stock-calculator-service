package com.zzh.stock_calculator.mcp.tool;

import com.zzh.stock_calculator.mcp.dict.StockDictEntry;
import com.zzh.stock_calculator.mcp.dict.StockDictMemoryService;
import com.zzh.stock_calculator.mcp.quote.DailyBar;
import com.zzh.stock_calculator.mcp.quote.QuoteFetchException;
import com.zzh.stock_calculator.mcp.quote.QuoteSyncService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * MCP 工具：个股原始日线序列（LLM 需要自行推理形态时使用；数量级受控，防上下文灌爆）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StockDailyTool {

    private final StockDictMemoryService dictService;
    private final QuoteSyncService quoteSyncService;

    @Tool(name = "stock_daily", description = "A股个股原始日线序列（日期/开/收/高/低/量，前复权）。"
            + "默认 60 根，范围 10-500。通常先用 stock_analysis 看指标，需要形态推理时再用本工具。")
    public Map<String, Object> daily(
            @ToolParam(description = "股票代码或名称，如 600519 / sh600519 / 贵州茅台") String stock,
            @ToolParam(required = false, description = "根数，默认 60，范围 10-500") Integer days) {
        Optional<StockDictEntry> entry = dictService.resolve(stock);
        if (entry.isEmpty()) {
            return Map.of("error", "无法解析股票: " + stock, "dictSize", dictService.size());
        }
        int n = days == null ? 60 : Math.min(Math.max(days, 10), 500);
        String stockId = entry.get().getStockId();
        try {
            List<DailyBar> bars = quoteSyncService.ensureBars(stockId, n);
            List<Map<String, Object>> rows = new ArrayList<>(bars.size());
            for (DailyBar b : bars) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("date", b.getDate().toString());
                row.put("open", round2(b.getOpen()));
                row.put("close", round2(b.getClose()));
                row.put("high", round2(b.getHigh()));
                row.put("low", round2(b.getLow()));
                row.put("volume", (long) b.getVolume());
                row.put("amount", Math.round(b.getAmount()));
                row.put("pctChg", b.getPctChg());
                row.put("turnover", b.getTurnover());
                rows.add(row);
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("stockId", stockId);
            out.put("name", entry.get().getName());
            out.put("count", rows.size());
            out.put("bars", rows);
            return out;
        } catch (QuoteFetchException e) {
            log.warn("daily 拉取失败 stockId={}: {}", stockId, e.getMessage());
            return Map.of("error", e.getMessage(), "stockId", stockId);
        }
    }

    private double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
