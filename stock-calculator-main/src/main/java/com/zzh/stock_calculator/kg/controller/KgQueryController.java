package com.zzh.stock_calculator.kg.controller;

import com.zzh.stock_calculator.common.ApiResponse;
import com.zzh.stock_calculator.kg.dto.KgQueryDtos.EntityDetailResponse;
import com.zzh.stock_calculator.kg.dto.KgQueryDtos.EntitySuggest;
import com.zzh.stock_calculator.kg.dto.KgQueryDtos.TimelineResponse;
import com.zzh.stock_calculator.kg.service.KgQueryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * kg 查询控制层（时序知识图谱前端可视化读侧，3 端点）：
 * 时间轴卡片流（搜索与默认浏览共用）/ 实体检索建议 / 实体详情摘要卡。
 * 鉴权由 AuthInterceptor 拦 /api/kg/**（WebConfig，与 /api/search/** 同法），
 * 无用户维度数据故不读 authUserId；异常经 GlobalExceptionHandler 统一转信封，
 * Controller 保持薄。
 */
@Slf4j
@RestController
@RequestMapping("/api/kg")
@RequiredArgsConstructor
public class KgQueryController {

    private final KgQueryService kgQueryService;

    /**
     * 时间轴卡片流（日分页）：无过滤参数 = 最近时间轴（默认态），带 keyword/entityId =
     * 搜索态。from/to 为 yyyy-MM-dd（约束归一化 event_time），page 0 起按「日」翻页。
     */
    @GetMapping("/timeline")
    public ApiResponse<TimelineResponse> timeline(
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "entityId", required = false) Long entityId,
            @RequestParam(value = "eventType", required = false) String eventType,
            @RequestParam(value = "from", required = false) String from,
            @RequestParam(value = "to", required = false) String to,
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "pageSize", required = false) Integer pageSize) {
        return ApiResponse.success(
                kgQueryService.timeline(keyword, entityId, eventType, from, to, page, pageSize));
    }

    /** 实体检索建议（搜索框补全，mention_count 倒序热实体优先）；空白 keyword 返回空数组 */
    @GetMapping("/entities/suggest")
    public ApiResponse<List<EntitySuggest>> suggest(
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "limit", required = false) Integer limit) {
        return ApiResponse.success(kgQueryService.suggest(keyword, limit));
    }

    /** 实体详情（摘要卡：别名/锚点/提及与参与事件数/高频共现实体 chips）；不存在 → 404 信封 */
    @GetMapping("/entities/{id}")
    public ApiResponse<EntityDetailResponse> entity(@PathVariable("id") Long id) {
        return ApiResponse.success(kgQueryService.entityDetail(id));
    }
}
