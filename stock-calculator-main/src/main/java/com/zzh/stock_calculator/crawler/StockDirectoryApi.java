package com.zzh.stock_calculator.crawler;

import com.zzh.stock_calculator.crawler.entity.Stock;
import com.zzh.stock_calculator.crawler.repository.StockRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * crawler 基包股票字典查询 API（backend-implementation §1；拍板 C12）。
 * 仅作 stockName 兜底与可选统计，不作 stock-profile 的 400 依据——
 * 字典来源于 CLS 每日任务 parseStockDicts upsert，覆盖不全（api 文档 §5 定案）。
 */
@Service
@RequiredArgsConstructor
public class StockDirectoryApi {

    /** 代码形态输入（裸 6 位或腾讯形态，大小写不限）——捕获组恒为 6 位数字本体 */
    private static final Pattern CODE_LIKE = Pattern.compile("^(?:sh|sz|bj)?(\\d{6})$", Pattern.CASE_INSENSITIVE);

    private final StockRepository stockRepository;

    /** 字典是否存在该代码 */
    public boolean existsByCode(String stockCode) {
        return stockCode != null && stockRepository.existsById(stockCode);
    }

    /**
     * 字典是否存在该 6 位码。
     * <p>防坑：字典键形态混杂——沪深为 sh600745 前缀形态、北交所为 920000.BJ 后缀形态，
     * 裸 6 位码直接 existsById 永远落空（broker klines 全量误 400 的实证根因），统一按尾部匹配。</p>
     */
    public boolean existsBySixDigit(String sixDigit) {
        return sixDigit != null && sixDigit.length() == 6
                && stockRepository.existsByStockIdEndingWithOrStockIdStartingWith(sixDigit, sixDigit + ".");
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

    /**
     * 代码形态输入 → 字典键解析（guide 中途入口归一化，docs/guide/design.md D11）：
     * 接受裸 6 位码（600519）或腾讯形态（sh601318，大小写不限），双形态查库——
     * 字典键混杂（沪深前缀/北交 .BJ 后缀），代码直查 name 或精确 findById 均永远落空
     * （guide analyze/brief 裸码断链实测根因，broker klines 同款坑先例）。未收录/形状不符返回 null。
     */
    public String resolveDictKey(String codeLike) {
        if (codeLike == null || codeLike.isBlank()) {
            return null;
        }
        Matcher matcher = CODE_LIKE.matcher(codeLike.trim());
        if (!matcher.matches()) {
            return null;
        }
        String sixDigit = matcher.group(1);
        return stockRepository
                .findFirstByStockIdEndingWithOrStockIdStartingWith(sixDigit, sixDigit + ".")
                .map(Stock::getStockId)
                .orElse(null);
    }

    private static String nameOf(Stock stock) {
        return stock.getName();
    }
}
