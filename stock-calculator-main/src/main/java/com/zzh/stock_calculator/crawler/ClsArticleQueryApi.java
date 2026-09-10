package com.zzh.stock_calculator.crawler;

import com.zzh.stock_calculator.crawler.entity.ClsArticleStock;
import com.zzh.stock_calculator.crawler.repository.ClsArticleStockRepository;
import com.zzh.stock_calculator.crawler.repository.StockRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * crawler 基包电报查询 API（backend-implementation §1；拍板 C12）：
 * cls_article_stock 关联口径查询，供 search 域组装 mention 与 P2 clsMention 预留。
 * entity 是内部类型，返回值一律用基包 record 载体（Modulith 红线）。
 */
@Service
@RequiredArgsConstructor
public class ClsArticleQueryApi {

    private final ClsArticleStockRepository stockLinkRepository;
    private final StockRepository stockRepository;

    /** articleId → 提及股票列表（按关联写入序；无提及 = 空列表；字典未收录时 name 兜底空串） */
    public Map<Long, List<Mention>> mentionsByArticleIds(Collection<Long> articleIds) {
        if (articleIds == null || articleIds.isEmpty()) {
            return Map.of();
        }
        List<ClsArticleStock> links = stockLinkRepository.findByArticleIdIn(articleIds);
        if (links.isEmpty()) {
            return Map.of();
        }
        Set<String> codes = new HashSet<>();
        for (ClsArticleStock link : links) {
            codes.add(link.getStockId());
        }
        Map<String, String> nameByCode = new HashMap<>();
        stockRepository.findAllById(codes).forEach(
                stock -> nameByCode.put(stock.getStockId(), stock.getName()));
        Map<Long, List<Mention>> result = new LinkedHashMap<>();
        for (ClsArticleStock link : links) {
            result.computeIfAbsent(link.getArticleId(), key -> new ArrayList<>())
                    .add(new Mention(link.getStockId(),
                            nameByCode.getOrDefault(link.getStockId(), "")));
        }
        return result;
    }

    /** 指定股票自 sinceCtime（秒）起被电报提及的文章数（P2 clsMention 预留） */
    public long countByStockCodeSince(String stockCode, long sinceCtime) {
        if (stockCode == null || stockCode.isBlank()) {
            return 0;
        }
        return stockLinkRepository.countDistinctArticleIdByStockIdSince(stockCode, sinceCtime);
    }

    /** 电报提及股票（基包公开载体；stockName 为字典快照名，未收录 = 空串） */
    public record Mention(String stockId, String stockName) {
    }
}
