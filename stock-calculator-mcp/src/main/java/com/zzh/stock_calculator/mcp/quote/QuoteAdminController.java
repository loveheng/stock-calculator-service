package com.zzh.stock_calculator.mcp.quote;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 行情管理端点（本地自用无鉴权）：手动全量重灌（除权漂移修复）+ 任意区间读取（全量分析口）。
 */
@RestController
@RequestMapping("/admin/quote")
@RequiredArgsConstructor
public class QuoteAdminController {

    private final QuoteSyncService syncService;
    private final QuoteDailyRepository repository;

    @PostMapping("/resync")
    public Map<String, Object> resync(@RequestParam String stockId,
                                      @RequestParam(defaultValue = "500") int days) {
        return syncService.forceResync(stockId, Math.min(Math.max(days, 30), 2000));
    }

    @GetMapping("/bars")
    public List<Map<String, Object>> bars(@RequestParam String stockId,
                                          @RequestParam String from,
                                          @RequestParam String to) {
        return syncService.readRange(stockId, LocalDate.parse(from), LocalDate.parse(to)).stream()
                .map(b -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("date", b.getDate().toString());
                    row.put("open", b.getOpen());
                    row.put("close", b.getClose());
                    row.put("high", b.getHigh());
                    row.put("low", b.getLow());
                    row.put("volume", (long) b.getVolume());
                    row.put("amount", b.getAmount());
                    row.put("amplitude", b.getAmplitude());
                    row.put("pctChg", b.getPctChg());
                    row.put("chg", b.getChg());
                    row.put("turnover", b.getTurnover());
                    return row;
                })
                .toList();
    }

    @GetMapping("/status")
    public Map<String, Object> status(@RequestParam String stockId) {
        return Map.of(
                "stockId", stockId,
                "stored", repository.countByStockId(stockId),
                "lastDate", String.valueOf(repository.findMaxTradeDate(stockId)));
    }
}
