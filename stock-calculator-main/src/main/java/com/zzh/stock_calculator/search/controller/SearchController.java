package com.zzh.stock_calculator.search.controller;

import com.zzh.stock_calculator.common.ApiResponse;
import com.zzh.stock_calculator.common.BusinessException;
import com.zzh.stock_calculator.common.RateLimitData;
import com.zzh.stock_calculator.common.RateLimitedException;
import com.zzh.stock_calculator.search.config.SearchProperties;
import com.zzh.stock_calculator.search.dto.SearchDtos.AnnouncementSearchRequest;
import com.zzh.stock_calculator.search.dto.SearchDtos.AnnouncementSearchResponse;
import com.zzh.stock_calculator.search.dto.SearchDtos.ClsSearchRequest;
import com.zzh.stock_calculator.search.dto.SearchDtos.ClsSearchResponse;
import com.zzh.stock_calculator.search.dto.SearchDtos.CompositeRequest;
import com.zzh.stock_calculator.search.dto.SearchDtos.CompositeResponse;
import com.zzh.stock_calculator.search.dto.SearchDtos.StockProfileResponse;
import com.zzh.stock_calculator.search.service.AnnouncementSearchService;
import com.zzh.stock_calculator.search.service.ClsSearchService;
import com.zzh.stock_calculator.search.service.CompositeSearchService;
import com.zzh.stock_calculator.search.service.StockProfileService;
import com.zzh.stock_calculator.search.util.SearchParamsValidator;
import com.zzh.stock_calculator.search.util.SearchParamsValidator.DateRange;
import com.zzh.stock_calculator.search.util.SearchRateLimiter;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 资讯搜索控制层（api 文档 §2/§3/§4/§5；backend-implementation §1）。
 * 鉴权：AuthInterceptor 拦截 /api/search/**（WebConfig），authUserId = 会话内用户 UUID 文本。
 * 异常交由 GlobalExceptionHandler（BusinessException→400 信封、RateLimitedException→429 信封带 data），
 * Controller 保持薄。共表红线（backend-implementation §8.4）：vector_store 与 cls 公表，
 * 后续新增检索端点必须带来源过滤。
 * C1 红线：检索历史不落库不持久化；日志不打 query/stockCodes 明文。
 */
@Slf4j
@RestController
@RequestMapping("/api/search")
@RequiredArgsConstructor
public class SearchController {

    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

    private final AnnouncementSearchService announcementSearchService;
    private final ClsSearchService clsSearchService;
    private final CompositeSearchService compositeSearchService;
    private final StockProfileService stockProfileService;
    private final SearchRateLimiter rateLimiter;
    private final SearchProperties properties;

    /** 公告摘要检索（api 文档 §2；校验 400 → 限流 429 → 检索） */
    @PostMapping("/announcements")
    public ApiResponse<AnnouncementSearchResponse> searchAnnouncements(
            @RequestAttribute("authUserId") String userId,
            @RequestBody AnnouncementSearchRequest request) {
        String query = SearchParamsValidator.validateQuery(request.getQuery());
        List<String> stockCodes = SearchParamsValidator.validateStockCodes(request.getStockCodes());
        DateRange dateRange = request.getDateRange() == null ? null
                : SearchParamsValidator.validateDateRange(
                        request.getDateRange().getStart(), request.getDateRange().getEnd());
        int topK = SearchParamsValidator.validateTopK(request.getTopK(),
                properties.getRetrieval().getDefaultTopK(), properties.getRetrieval().getMaxTopK());
        rateLimiter.checkSearch(userId);
        long start = System.currentTimeMillis();
        AnnouncementSearchResponse response = announcementSearchService.search(query, stockCodes, dateRange, topK);
        log.info("POST /api/search/announcements: topK={}, cost={}ms",
                topK, System.currentTimeMillis() - start);
        return ApiResponse.success(response);
    }

    /** 股票档案卡（api 文档 §5；格式非法 400 → 限流 → 聚合；合法未收录 → 200 空列表） */
    @GetMapping("/stock-profile")
    public ApiResponse<StockProfileResponse> stockProfile(
            @RequestAttribute("authUserId") String userId,
            @RequestParam("stockId") String stockId) {
        String code = SearchParamsValidator.validateStockId(stockId);
        rateLimiter.checkSearch(userId);
        long start = System.currentTimeMillis();
        StockProfileResponse response = stockProfileService.profile(code);
        log.info("GET /api/search/stock-profile: cost={}ms", System.currentTimeMillis() - start);
        return ApiResponse.success(response);
    }

    /** 电报检索（api 文档 §3；校验 400 → 限流 429 → 检索） */
    @PostMapping("/cls")
    public ApiResponse<ClsSearchResponse> searchCls(
            @RequestAttribute("authUserId") String userId,
            @RequestBody ClsSearchRequest request) {
        String query = SearchParamsValidator.validateQuery(request.getQuery());
        DateRange dateRange = request.getDateRange() == null ? null
                : SearchParamsValidator.validateDateRange(
                        request.getDateRange().getStart(), request.getDateRange().getEnd());
        int topK = SearchParamsValidator.validateTopK(request.getTopK(),
                properties.getRetrieval().getDefaultTopK(), properties.getRetrieval().getMaxTopK());
        rateLimiter.checkSearch(userId);
        long start = System.currentTimeMillis();
        ClsSearchResponse response = clsSearchService.search(query, dateRange, topK);
        log.info("POST /api/search/cls: topK={}, cost={}ms",
                topK, System.currentTimeMillis() - start);
        return ApiResponse.success(response);
    }

    /** 综合摘要 JSON 变体（api 文档 §4 方案 B；无 Accept: text/event-stream 时命中） */
    @PostMapping("/composite")
    public ApiResponse<CompositeResponse> composite(
            @RequestAttribute("authUserId") String userId,
            @RequestBody CompositeRequest request) {
        String query = SearchParamsValidator.validateQuery(request.getQuery());
        List<String> stockCodes = SearchParamsValidator.validateStockCodes(request.getStockCodes());
        DateRange dateRange = validateDateRange(request);
        rateLimiter.checkComposite(userId);
        long start = System.currentTimeMillis();
        CompositeResponse response = compositeSearchService.composite(query, stockCodes, dateRange);
        log.info("POST /api/search/composite: cost={}ms", System.currentTimeMillis() - start);
        return ApiResponse.success(response);
    }

    /**
     * 综合摘要 SSE 流式变体（api 文档 §4 方案 A；Accept: text/event-stream 命中）。
     * 阶段一（校验/限流/双库检索）在返回 emitter 前同步执行；失败回落恒 200 JSON 信封
     * ——Accept 仅 event-stream 时异常 advice 的 JSON 会因内容协商 406，
     * 故手工以 application/json 写出同一信封（照抄 copilot writeJsonFallback 模式，含 429 data）。
     */
    @PostMapping(value = "/composite", headers = "Accept=text/event-stream",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter compositeStream(
            @RequestAttribute("authUserId") String userId,
            @RequestBody CompositeRequest request,
            HttpServletResponse response) throws java.io.IOException {
        try {
            String query = SearchParamsValidator.validateQuery(request.getQuery());
            List<String> stockCodes = SearchParamsValidator.validateStockCodes(request.getStockCodes());
            DateRange dateRange = validateDateRange(request);
            rateLimiter.checkComposite(userId);
            return compositeSearchService.compositeStream(query, stockCodes, dateRange);
        } catch (BusinessException e) {
            log.warn("Business error: code={}, msg={}", e.getCode(), e.getMessage());
            return writeJsonFallback(response, ApiResponse.fail(e.getCode(), e.getMessage()));
        } catch (RateLimitedException e) {
            log.warn("Rate limited: retryAfterSeconds={}", e.getRetryAfterSeconds());
            return writeJsonFallback(response, ApiResponse.fail(429, e.getMessage(),
                    RateLimitData.builder().retryAfterSeconds(e.getRetryAfterSeconds()).build()));
        } catch (Exception e) {
            log.error("Unexpected error: ", e);
            return writeJsonFallback(response, ApiResponse.fail(500, "服务器内部错误"));
        }
    }

    /** composite 请求的 dateRange 校验（String 手动解析保 400 文案） */
    private static DateRange validateDateRange(CompositeRequest request) {
        return request.getDateRange() == null ? null
                : SearchParamsValidator.validateDateRange(
                        request.getDateRange().getStart(), request.getDateRange().getEnd());
    }

    /** 阶段一失败回落：手工写既有 JSON 信封并提交响应，返回 null 表示响应已处理 */
    private SseEmitter writeJsonFallback(HttpServletResponse response, ApiResponse<?> body)
            throws java.io.IOException {
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(JSON_MAPPER.writeValueAsString(body));
        response.getWriter().flush();
        return null;
    }
}
