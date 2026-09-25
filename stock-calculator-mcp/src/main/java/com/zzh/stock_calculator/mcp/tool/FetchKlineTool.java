package com.zzh.stock_calculator.mcp.tool;

import com.zzh.stock_calculator.mcp.dict.StockDictEntry;
import com.zzh.stock_calculator.mcp.dict.StockDictMemoryService;
import com.zzh.stock_calculator.mcp.quote.DailyBar;
import com.zzh.stock_calculator.mcp.quote.DailyQuoteClient;
import com.zzh.stock_calculator.mcp.quote.QuoteFetchException;
import com.zzh.stock_calculator.mcp.quote.QuoteSyncService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * MCP 工具：画布 K 线读穿代理（free-canvas v3 §3.4 一跳读穿）。
 * <p>查库 quote_daily → 不足自动全量/增量拉腾讯 → ON CONFLICT 幂等入库 → 返回升序切片。
 * qfq 为库权威口径（拉取即入库，库即缓存）；raw 仅读穿返回不入库
 * （quote_daily 唯一约束 (stock_id, trade_date) 只容单一复权基准，防两基准硬拼）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FetchKlineTool {

    /** 缺口告警阈值（生死线#2 连续性记录，日志级）：正常最长节假日缺口（春节+周末）约 9 日历日 */
    private static final int GAP_ALERT_DAYS = 12;

    private final StockDictMemoryService dictService;
    private final QuoteSyncService quoteSyncService;
    private final DailyQuoteClient quoteClient;

    @Tool(name = "fetch_kline", description = "画布 K 线读穿代理：查库覆盖，不足自动拉取入库，返回升序日线切片"
            + "（date/open/close/high/low/volume，日期字符串 YYYY-MM-DD）。"
            + "qfq 前复权为默认与库权威口径；raw 不复权仅读穿不入库。")
    public Map<String, Object> fetchKline(
            @ToolParam(description = "股票代码或名称，如 600519 / sh600519 / 贵州茅台") String stock,
            @ToolParam(required = false, description = "复权类型：qfq（默认，前复权，入库）| raw（不复权，只读穿）") String adjustType,
            @ToolParam(required = false, description = "起始日期 YYYY-MM-DD（含），默认近 365 日历日") String from,
            @ToolParam(required = false, description = "截止日期 YYYY-MM-DD（含），默认最新") String to,
            @ToolParam(required = false, description = "调用方追踪 ID，原样回传") String snapshotId) {
        Optional<StockDictEntry> entry = dictService.resolve(stock);
        if (entry.isEmpty()) {
            return Map.of("error", "无法解析股票: " + stock, "dictSize", dictService.size());
        }
        String type = adjustType == null || adjustType.isBlank() ? "qfq" : adjustType.toLowerCase();
        if (!"qfq".equals(type) && !"raw".equals(type)) {
            return Map.of("error", "不支持的复权类型(仅 qfq/raw): " + adjustType, "stockId", entry.get().getStockId());
        }
        LocalDate endDate = parseDate(to, "to", entry.get().getStockId());
        if (endDate == null) {
            return error("to", entry.get().getStockId());
        }
        LocalDate beginDate = endDate;
        if (from != null && !from.isBlank()) {
            beginDate = parseDate(from, "from", entry.get().getStockId());
            if (beginDate == null) {
                return error("from", entry.get().getStockId());
            }
        } else {
            beginDate = endDate.minusDays(365);
        }
        if (beginDate.isAfter(endDate)) {
            return Map.of("error", "日期区间倒置: from > to", "stockId", entry.get().getStockId());
        }
        String stockId = entry.get().getStockId();
        try {
            List<DailyBar> bars = "raw".equals(type)
                    ? fetchRawSlice(stockId, beginDate, endDate)
                    : quoteSyncService.ensureRange(stockId, beginDate, endDate);
            checkContinuity(stockId, bars);
            return buildOut(entry.get(), type, bars, beginDate, endDate, snapshotId);
        } catch (QuoteFetchException e) {
            log.warn("fetch_kline 拉取失败 stockId={} [{}~{}]: {}", stockId, beginDate, endDate, e.getMessage());
            return Map.of("error", e.getMessage(), "stockId", stockId, "coverage", Map.of());
        }
    }

    /** raw 读穿：拉 beg→最新（fqt=0），内存过滤到 to，不入库 */
    private List<DailyBar> fetchRawSlice(String stockId, LocalDate beginDate, LocalDate endDate) {
        return quoteClient.fetchRawWindow(stockId, beginDate).stream()
                .filter(b -> !b.getDate().isBefore(beginDate) && !b.getDate().isAfter(endDate))
                .toList();
    }

    /** 生死线#2：连续性缺口检测，只记录（日志级）不阻断——后续按需请求自然补齐 */
    private void checkContinuity(String stockId, List<DailyBar> bars) {
        for (int i = 1; i < bars.size(); i++) {
            long gap = java.time.temporal.ChronoUnit.DAYS.between(bars.get(i - 1).getDate(), bars.get(i).getDate());
            if (gap > GAP_ALERT_DAYS) {
                log.warn("行情连续性缺口: {} {} -> {}（{} 日历日）", stockId, bars.get(i - 1).getDate(),
                        bars.get(i).getDate(), gap);
            }
        }
    }

    private Map<String, Object> buildOut(StockDictEntry entry, String type, List<DailyBar> bars,
                                         LocalDate beginDate, LocalDate endDate, String snapshotId) {
        List<Map<String, Object>> rows = new ArrayList<>(bars.size());
        for (DailyBar b : bars) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("date", b.getDate().toString());
            row.put("open", round(b.getOpen()));
            row.put("close", round(b.getClose()));
            row.put("high", round(b.getHigh()));
            row.put("low", round(b.getLow()));
            row.put("volume", (long) b.getVolume());
            row.put("amount", Math.round(b.getAmount()));
            row.put("pctChg", Math.round(b.getPctChg() * 100.0) / 100.0);
            row.put("turnover", Math.round(b.getTurnover() * 100.0) / 100.0);
            rows.add(row);
        }
        Map<String, Object> coverage = new LinkedHashMap<>();
        coverage.put("from", bars.isEmpty() ? beginDate.toString() : bars.get(0).getDate().toString());
        coverage.put("to", bars.isEmpty() ? endDate.toString() : bars.get(bars.size() - 1).getDate().toString());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("stockId", stockKey(entry));
        out.put("name", entry.getName());
        out.put("adjustType", type);
        out.put("persisted", !"raw".equals(type));
        out.put("requested", Map.of("from", beginDate.toString(), "to", endDate.toString()));
        out.put("coverage", coverage);
        out.put("count", rows.size());
        out.put("klines", rows);
        if (snapshotId != null && !snapshotId.isBlank()) {
            out.put("snapshotId", snapshotId);
        }
        return out;
    }

    /** 对外统一输出腾讯形态 sh600745 / bj920000（main 直接透传前端，前端零转换）；字典后缀形态 .BJ 就地转前缀 */
    private String stockKey(StockDictEntry entry) {
        String id = entry.getStockId();
        if (id == null) {
            return id;
        }
        // 字典北交键为后缀形态（920000.BJ），统一转腾讯前缀形态
        String lower = id.toLowerCase();
        if (lower.length() == 9
                && (lower.endsWith(".sh") || lower.endsWith(".sz") || lower.endsWith(".bj"))) {
            return lower.substring(7) + lower.substring(0, 6);
        }
        if (id.length() == 6 && id.chars().allMatch(Character::isDigit)) {
            // 腾讯形态市场前缀：6 沪；4/8/9 北交所（含 43/83/87/920 段）；其余深
            String market = id.startsWith("6") ? "sh"
                    : id.startsWith("4") || id.startsWith("8") || id.startsWith("9") ? "bj" : "sz";
            return market + id;
        }
        return id;
    }

    private Map<String, Object> error(String field, String stockId) {
        return Map.of("error", "日期格式非法（需 YYYY-MM-DD）: " + field, "stockId", stockId);
    }

    private LocalDate parseDate(String raw, String field, String stockId) {
        if (raw == null || raw.isBlank()) {
            return field.equals("to") ? LocalDate.now() : null;
        }
        try {
            return LocalDate.parse(raw.trim());
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private double round(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }
}
