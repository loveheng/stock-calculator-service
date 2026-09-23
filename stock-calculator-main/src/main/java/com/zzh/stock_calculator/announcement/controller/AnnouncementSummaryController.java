package com.zzh.stock_calculator.announcement.controller;

import com.zzh.stock_calculator.announcement.AnnouncementQueryApi;
import com.zzh.stock_calculator.announcement.AnnouncementView;
import com.zzh.stock_calculator.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 公告摘要批量查询端点（orchestration 2② 财报对比链路数据源，只读）：
 * 按 announcementId 集合批量返回摘要——orchestration Executor 的 rest 工具节点
 * （foreach 展开按 doc_id 抽取后送 LLM 汇总对比）经 tool_registry 调用。
 * 只回 DONE 且 summary 非空的有效项；identity 集合超过 20 个拒绝（foreach 单节点上限，防滥用）。
 */
@RestController
@RequestMapping("/api/announcement/summaries")
@RequiredArgsConstructor
public class AnnouncementSummaryController {

    /** foreach 编排单次批量上限：超过视为误用（LLM 汇总也吃不下） */
    static final int MAX_IDS = 20;

    private final AnnouncementQueryApi queryApi;

    @GetMapping
    public ApiResponse<List<SummaryItem>> batch(@RequestParam("ids") List<String> ids) {
        if (ids == null || ids.isEmpty() || ids.size() > MAX_IDS) {
            throw new IllegalArgumentException("ids 必填且不超过 " + MAX_IDS + " 个");
        }
        List<SummaryItem> items = queryApi.findAllByAnnouncementIdIn(ids).stream()
                .filter(v -> v.summary() != null && !v.summary().isBlank())
                .map(v -> new SummaryItem(v.announcementId(), v.title(), v.secCode(),
                        v.secName(), v.seDate() == null ? null : v.seDate().toString(), v.summary()))
                .toList();
        return ApiResponse.success(items);
    }

    /** 精简摘要条目：只给对比所需最小集（不回 sourceUrl/adjunctUrl 等下载元数据） */
    public record SummaryItem(String announcementId, String title, String secCode,
                              String secName, String seDate, String summary) {
    }
}
