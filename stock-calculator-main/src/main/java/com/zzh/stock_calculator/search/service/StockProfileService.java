package com.zzh.stock_calculator.search.service;

import com.zzh.stock_calculator.announcement.AnnouncementQueryApi;
import com.zzh.stock_calculator.announcement.AnnouncementView;
import com.zzh.stock_calculator.crawler.StockDirectoryApi;
import com.zzh.stock_calculator.search.dto.SearchDtos.LatestAnnouncement;
import com.zzh.stock_calculator.search.dto.SearchDtos.StockProfileResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 股票档案卡聚合（backend-implementation §1 M3，api 文档 §5）：
 * latestAnnouncements = 最新 1~3 条 DONE 且 summary 非空（annDate 倒序）；
 * clsMention 一期恒 null（P2 就绪后回填，契约已约定前端隐藏区块）。
 * <p>未知股票语义（api 文档 §5 定案）：格式合法但语料未收录 → 200 + 空列表 + clsMention=null；
 * stockName 兜底链 = announcement.secName → stock 字典 → 空串（前端行情补）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StockProfileService {

    private static final int LATEST_LIMIT = 3;

    private final AnnouncementQueryApi announcementQueryApi;
    private final StockDirectoryApi stockDirectoryApi;

    public StockProfileResponse profile(String stockId) {
        List<AnnouncementView> latest =
                announcementQueryApi.findDoneWithSummaryBySecCode(stockId, LATEST_LIMIT);
        String stockName = latest.stream()
                .map(AnnouncementView::secName)
                .filter(name -> name != null && !name.isBlank())
                .findFirst()
                .orElseGet(() -> stockDirectoryApi.nameByCode(stockId));
        List<LatestAnnouncement> items = latest.stream()
                .map(view -> LatestAnnouncement.builder()
                        .annId(view.announcementId())
                        .annDate(view.seDate() == null ? null : view.seDate().toString())
                        .title(view.title())
                        .summary(view.summary() == null ? "" : view.summary().trim())
                        .build())
                .toList();
        return StockProfileResponse.builder()
                .stockId(stockId)
                .stockName(stockName == null ? "" : stockName)
                .latestAnnouncements(items)
                .clsMention(null) // P2 数据（backend-implementation §7），契约约定前端隐藏区块
                .build();
    }
}
