package com.zzh.stock_calculator.crawler;

import com.zzh.stock_calculator.crawler.entity.Stock;
import com.zzh.stock_calculator.crawler.repository.StockRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * crawler 基包股票字典查询 API（backend-implementation §1；拍板 C12）。
 * 仅作 stockName 兜底与可选统计，不作 stock-profile 的 400 依据——
 * 字典来源于 CLS 每日任务 parseStockDicts upsert，覆盖不全（api 文档 §5 定案）。
 */
@Service
@RequiredArgsConstructor
public class StockDirectoryApi {

    private final StockRepository stockRepository;

    /** 字典是否存在该代码 */
    public boolean existsByCode(String stockCode) {
        return stockCode != null && stockRepository.existsById(stockCode);
    }

    /** 字典名称；未收录返回 null（调用方自行兜底） */
    public String nameByCode(String stockCode) {
        return stockCode == null ? null
                : stockRepository.findById(stockCode).map(StockDirectoryApi::nameOf).orElse(null);
    }

    /** 批量取名称映射（仅含字典已收录项） */
    public Map<String, String> namesByCodes(Collection<String> stockCodes) {
        if (stockCodes == null || stockCodes.isEmpty()) {
            return Map.of();
        }
        return stockRepository.findAllById(stockCodes).stream()
                .collect(Collectors.toMap(Stock::getStockId, StockDirectoryApi::nameOf, (a, b) -> a));
    }

    private static String nameOf(Stock stock) {
        return stock.getName();
    }
}
