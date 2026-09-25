package com.zzh.stock_calculator.mcp.indicator;

import com.zzh.stock_calculator.mcp.quote.DailyBar;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * compute 序列口径契约测试（free-canvas §3.1）：数组与输入一一对齐、暖机 null 占位、
 * 不足 minBars 整体 null、白名单外拒绝。
 */
class IndicatorSeriesServiceTest {

    private final IndicatorSeriesService service = new IndicatorSeriesService();

    private List<DailyBar> bars(int n) {
        LocalDate start = LocalDate.of(2024, 1, 2);
        List<DailyBar> list = new java.util.ArrayList<>();
        for (int i = 0; i < n; i++) {
            double base = 10 + i * 0.1;
            list.add(DailyBar.builder()
                    .date(start.plusDays(i))
                    .open(base).close(base + 0.1).high(base + 0.2).low(base - 0.1)
                    .volume(1000 + i).build());
        }
        return list;
    }

    @Test
    void arraysAlignWithInputAndNullWarmup() {
        List<DailyBar> bars = bars(60);
        Map<String, Object> out = service.compute(bars, List.of("macd", "kdj", "boll"));

        // 整体结构：不裁短，槽位对齐输入根数
        @SuppressWarnings("unchecked")
        Map<String, List<Double>> macd = (Map<String, List<Double>>) out.get("macd");
        assertEquals(60, macd.get("macd").size());
        assertNull(macd.get("macd").get(31));       // 暖机前 null 占位（index < 32）
        assertTrue(macd.get("macd").get(32) != null); // 第 33 根起有值

        @SuppressWarnings("unchecked")
        Map<String, List<Double>> boll = (Map<String, List<Double>>) out.get("boll");
        assertNull(boll.get("mid").get(18));
        assertTrue(boll.get("mid").get(19) != null);
    }

    @Test
    void insufficientBarsYieldsWholeIndicatorNull() {
        Map<String, Object> out = service.compute(bars(20), List.of("macd", "kdj", "boll"));
        assertNull(out.get("macd"));   // < 33 根整体 null
        assertTrue(out.get("kdj") instanceof Map); // ≥ 10 根正常出
        assertTrue(out.get("boll") instanceof Map); // = 20 根恰好达 minBars，出值
        Map<String, Object> out19 = service.compute(bars(19), List.of("boll"));
        assertNull(out19.get("boll")); // < 20 根整体 null（minBars 边界）
    }

    @Test
    void unknownIndicatorRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> service.compute(bars(60), List.of("rsi")));
    }
}
