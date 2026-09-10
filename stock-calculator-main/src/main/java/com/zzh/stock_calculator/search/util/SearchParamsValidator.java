package com.zzh.stock_calculator.search.util;

import com.zzh.stock_calculator.common.BusinessException;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 搜索入参校验纯函数（backend-implementation §7，单测友好；Controller 保持薄）。
 * 错误文案与 api 文档 §7 表格逐字对齐。
 */
public final class SearchParamsValidator {

    /** 6 位数字股票代码（与订阅接口同正则口径，AnnouncementSubscriptionService.STOCK_ID_PATTERN） */
    public static final Pattern STOCK_ID_PATTERN = Pattern.compile("\\d{6}");

    private static final int QUERY_MAX = 64;
    private static final int STOCK_CODES_MAX = 50;
    private static final long DATE_SPAN_MAX_YEARS = 3;

    private SearchParamsValidator() {
    }

    /** query：trim 后 1~64 字符，返回 trim 结果 */
    public static String validateQuery(String query) {
        String trimmed = query == null ? "" : query.trim();
        if (trimmed.isEmpty() || trimmed.length() > QUERY_MAX) {
            throw new BusinessException(400, "检索关键词需 1~64 个字符");
        }
        return trimmed;
    }

    /** topK：null/≤0 → 缺省 defaultTopK；上限 maxTopK（超出 400「检索范围过大」） */
    public static int validateTopK(Integer topK, int defaultTopK, int maxTopK) {
        int effective = (topK == null || topK <= 0) ? defaultTopK : topK;
        if (effective > maxTopK) {
            throw new BusinessException(400, "检索范围过大");
        }
        return effective;
    }

    /**
     * stockCodes：逐项 6 位数字码；去重保序后 ≤50。
     * null/空 → null（= 不限股票）；非法项 → 400「stockId 非法」。
     */
    public static List<String> validateStockCodes(List<String> stockCodes) {
        if (stockCodes == null || stockCodes.isEmpty()) {
            return null;
        }
        LinkedHashSet<String> deduped = new LinkedHashSet<>();
        for (String raw : stockCodes) {
            String code = raw == null ? "" : raw.trim();
            if (!STOCK_ID_PATTERN.matcher(code).matches()) {
                throw new BusinessException(400, "stockId 非法");
            }
            deduped.add(code);
        }
        if (deduped.size() > STOCK_CODES_MAX) {
            throw new BusinessException(400, "检索范围过大");
        }
        return List.copyOf(deduped);
    }

    /**
     * dateRange（字符串入参，手动解析保证 400 文案而非 500）：
     * YYYY-MM-DD；start ≤ end；跨度 ≤3 年（语料下界 2023-01-01）。null/两端均空 → null（不限时间）。
     */
    public static DateRange validateDateRange(String start, String end) {
        if ((start == null || start.isBlank()) && (end == null || end.isBlank())) {
            return null;
        }
        LocalDate startDate;
        LocalDate endDate;
        try {
            startDate = LocalDate.parse(start == null ? "" : start.trim());
            endDate = LocalDate.parse(end == null ? "" : end.trim());
        } catch (DateTimeParseException e) {
            throw new BusinessException(400, "日期范围无效");
        }
        if (startDate.isAfter(endDate) || ChronoUnit.YEARS.between(startDate, endDate) > DATE_SPAN_MAX_YEARS) {
            throw new BusinessException(400, "日期范围无效");
        }
        return new DateRange(startDate, endDate);
    }

    /** stock-profile 的 stockId 校验（与订阅接口同口径），返回 trim 结果 */
    public static String validateStockId(String stockId) {
        String trimmed = stockId == null ? "" : stockId.trim();
        if (!STOCK_ID_PATTERN.matcher(trimmed).matches()) {
            throw new BusinessException(400, "stockId 非法：须为 6 位数字股票代码");
        }
        return trimmed;
    }

    /** 闭区间日期范围 */
    public record DateRange(LocalDate start, LocalDate end) {
    }
}
