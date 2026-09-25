package com.zzh.stock_calculator.search;

import com.zzh.stock_calculator.search.dto.SearchDtos.LatestAnnouncement;
import com.zzh.stock_calculator.search.dto.SearchDtos.StockProfileResponse;
import com.zzh.stock_calculator.search.service.StockProfileService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * search 基包股票档案卡 API（guide 引导跨域消费口，docs/guide/design.md §五）：
 * 委托 service/StockProfileService 聚合并收敛为基包 record 载体——guide 只见基包类型，
 * 不触碰 search.dto 子包（Modulith 红线，同 ClsArticleQueryApi 门面惯例）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StockProfileApi {

    private final StockProfileService stockProfileService;

    /**
     * 个股档案卡（最新 1~3 条 DONE 且 summary 非空的公告蒸馏摘要，annDate 倒序）。
     * 未知股票语义沿用档案卡定案：格式合法但语料未收录 → 空列表 + 空名，不报错。
     */
    public StockProfile profile(String stockId) {
        if (stockId == null || stockId.isBlank()) {
            return new StockProfile("", "", List.of());
        }
        StockProfileResponse response = stockProfileService.profile(stockId.trim());
        List<LatestAnnouncement> latest = response.getLatestAnnouncements() == null
                ? List.of() : response.getLatestAnnouncements();
        List<AnnouncementBrief> items = latest.stream()
                .map(a -> new AnnouncementBrief(a.getAnnDate(), a.getTitle(), a.getSummary()))
                .toList();
        return new StockProfile(response.getStockId(), response.getStockName(), items);
    }

    /** 档案卡载体（stockName 兜底链 = 公告 secName → stock 字典 → 空串） */
    public record StockProfile(String stockId, String stockName, List<AnnouncementBrief> announcements) {
    }

    /** 公告摘要条目载体 */
    public record AnnouncementBrief(String annDate, String title, String summary) {
    }
}
