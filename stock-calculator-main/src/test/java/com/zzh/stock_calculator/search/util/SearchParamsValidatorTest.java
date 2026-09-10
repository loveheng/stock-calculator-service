package com.zzh.stock_calculator.search.util;

import com.zzh.stock_calculator.common.BusinessException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * SearchParamsValidator 纯函数单测：错误文案与 api 文档 §7 表格逐字对齐（backend-implementation §7）。
 */
class SearchParamsValidatorTest {

    // ==================== query ====================

    @Test
    void queryTrimsAndAcceptsBoundary() {
        assertEquals("半导体", SearchParamsValidator.validateQuery("  半导体  "));
        assertEquals("a".repeat(64), SearchParamsValidator.validateQuery("a".repeat(64)));
    }

    @Test
    void queryBlankOrNullRejected() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> SearchParamsValidator.validateQuery("   "));
        assertEquals(400, ex.getCode());
        assertEquals("检索关键词需 1~64 个字符", ex.getMessage());
        assertThrows(BusinessException.class, () -> SearchParamsValidator.validateQuery(null));
    }

    @Test
    void queryOver64Rejected() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> SearchParamsValidator.validateQuery("a".repeat(65)));
        assertEquals("检索关键词需 1~64 个字符", ex.getMessage());
    }

    // ==================== topK ====================

    @Test
    void topKDefaultsApplied() {
        assertEquals(10, SearchParamsValidator.validateTopK(null, 10, 50));
        assertEquals(10, SearchParamsValidator.validateTopK(0, 10, 50));
        assertEquals(10, SearchParamsValidator.validateTopK(-3, 10, 50));
    }

    @Test
    void topKWithinMaxPasses() {
        assertEquals(5, SearchParamsValidator.validateTopK(5, 10, 50));
        assertEquals(50, SearchParamsValidator.validateTopK(50, 10, 50));
    }

    @Test
    void topKOverMaxRejected() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> SearchParamsValidator.validateTopK(51, 10, 50));
        assertEquals(400, ex.getCode());
        assertEquals("检索范围过大", ex.getMessage());
    }

    // ==================== stockCodes ====================

    @Test
    void stockCodesNullOrBlankReturnsNull() {
        assertNull(SearchParamsValidator.validateStockCodes(null));
        assertNull(SearchParamsValidator.validateStockCodes(List.of()));
    }

    @Test
    void stockCodesDedupePreservingOrder() {
        List<String> result = SearchParamsValidator.validateStockCodes(
                List.of("600745", "000001", "600745"));
        assertEquals(List.of("600745", "000001"), result);
    }

    @Test
    void stockCodesIllegalRejected() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> SearchParamsValidator.validateStockCodes(List.of("600745", "ABC")));
        assertEquals(400, ex.getCode());
        assertEquals("stockId 非法", ex.getMessage());
        assertThrows(BusinessException.class,
                () -> SearchParamsValidator.validateStockCodes(List.of("60074")));
        assertThrows(BusinessException.class,
                () -> SearchParamsValidator.validateStockCodes(List.of("6007451")));
    }

    @Test
    void stockCodesOver50Rejected() {
        List<String> codes = new ArrayList<>();
        for (int i = 0; i < 51; i++) {
            codes.add(String.format("%06d", i));
        }
        BusinessException ex = assertThrows(BusinessException.class,
                () -> SearchParamsValidator.validateStockCodes(codes));
        assertEquals("检索范围过大", ex.getMessage());
    }

    // ==================== dateRange ====================

    @Test
    void dateRangeNullOrBlankReturnsNull() {
        assertNull(SearchParamsValidator.validateDateRange(null, null));
        assertNull(SearchParamsValidator.validateDateRange("", ""));
        assertNull(SearchParamsValidator.validateDateRange(" ", " "));
    }

    @Test
    void dateRangeValid() {
        SearchParamsValidator.DateRange range =
                SearchParamsValidator.validateDateRange("2025-09-01", "2025-09-10");
        assertEquals("2025-09-01", range.start().toString());
        assertEquals("2025-09-10", range.end().toString());
    }

    @Test
    void dateRangeInvalidFormatRejected() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> SearchParamsValidator.validateDateRange("20250901", "2025-09-10"));
        assertEquals(400, ex.getCode());
        assertEquals("日期范围无效", ex.getMessage());
        assertThrows(BusinessException.class,
                () -> SearchParamsValidator.validateDateRange("2025-09-01", "not-a-date"));
    }

    @Test
    void dateRangeStartAfterEndRejected() {
        assertThrows(BusinessException.class,
                () -> SearchParamsValidator.validateDateRange("2025-09-10", "2025-09-01"));
    }

    @Test
    void dateRangeSpanOver3YearsRejected() {
        assertThrows(BusinessException.class,
                () -> SearchParamsValidator.validateDateRange("2022-09-10", "2026-09-10"));
        // 恰好 3 年 = 合法
        assertDoesNotThrow(() -> SearchParamsValidator.validateDateRange("2023-09-10", "2026-09-10"));
    }

    // ==================== stockId（stock-profile） ====================

    @Test
    void stockIdTrimsAndAcceptsSixDigits() {
        assertEquals("600745", SearchParamsValidator.validateStockId(" 600745 "));
    }

    @Test
    void stockIdIllegalRejected() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> SearchParamsValidator.validateStockId("60074"));
        assertEquals(400, ex.getCode());
        assertEquals("stockId 非法：须为 6 位数字股票代码", ex.getMessage());
        assertThrows(BusinessException.class, () -> SearchParamsValidator.validateStockId(null));
        assertThrows(BusinessException.class, () -> SearchParamsValidator.validateStockId("sh600745"));
    }
}
