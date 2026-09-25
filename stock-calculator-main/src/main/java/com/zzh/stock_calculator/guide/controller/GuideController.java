package com.zzh.stock_calculator.guide.controller;

import com.zzh.stock_calculator.common.ApiResponse;
import com.zzh.stock_calculator.common.BusinessException;
import com.zzh.stock_calculator.guide.dto.GuideDtos.AnalyzeMessageRequest;
import com.zzh.stock_calculator.guide.dto.GuideDtos.AnalyzeMessageResponse;
import com.zzh.stock_calculator.guide.dto.GuideDtos.StockBriefResponse;
import com.zzh.stock_calculator.guide.service.GuideAnalyzeService;
import com.zzh.stock_calculator.guide.service.GuideStockBriefService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 选股引导控制层（docs/guide/design.md §四）：两步向导 REST 面板。
 * 鉴权：**不挂** AuthInterceptor——本端点族同时是 orchestration 工具面
 * （main.guide.analyze_message / main.guide.stock_brief），ToolInvoker 无用户会话，
 * 对齐 main.announcement.summaries 先例（只读聚合 + 本地自用裸跑）。
 * 异常交 GlobalExceptionHandler（BusinessException→400 信封）；日志不打消息明文（C1 同款红线）。
 */
@Slf4j
@RestController
@RequestMapping("/api/guide")
@RequiredArgsConstructor
public class GuideController {

    private final GuideAnalyzeService guideAnalyzeService;
    private final GuideStockBriefService guideStockBriefService;

    /** Step1 消息→候选股（dispatch 工具 main.guide.analyze_message 的后端） */
    @PostMapping("/analyze-message")
    public ApiResponse<AnalyzeMessageResponse> analyzeMessage(@RequestBody AnalyzeMessageRequest request) {
        if (request == null || request.getMessage() == null) {
            throw new BusinessException(400, "消息内容不能为空");
        }
        long start = System.currentTimeMillis();
        AnalyzeMessageResponse response =
                guideAnalyzeService.analyze(request.getMessage(), request.getDays());
        log.info("POST /api/guide/analyze-message: days={}, messageLen={}, candidates={}, cost={}ms",
                request.getDays() == null ? 7 : request.getDays(),
                request.getMessage().length(),
                response.getCandidates() == null ? 0 : response.getCandidates().size(),
                System.currentTimeMillis() - start);
        return ApiResponse.success(response);
    }

    /** Step2 个股引导档案（dispatch 工具 main.guide.stock_brief 的后端） */
    @GetMapping("/stock-brief")
    public ApiResponse<StockBriefResponse> stockBrief(
            @RequestParam("stockId") String stockId,
            @RequestParam(value = "days", required = false) Integer days) {
        long start = System.currentTimeMillis();
        StockBriefResponse response = guideStockBriefService.brief(stockId, days);
        log.info("GET /api/guide/stock-brief: days={}, cost={}ms", days, System.currentTimeMillis() - start);
        return ApiResponse.success(response);
    }
}
