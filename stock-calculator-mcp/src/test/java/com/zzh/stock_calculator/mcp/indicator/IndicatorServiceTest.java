package com.zzh.stock_calculator.mcp.indicator;

import com.zzh.stock_calculator.mcp.quote.DailyBar;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 指标数值对照：线性上行序列（closes=1..80）手算值断言。
 */
class IndicatorServiceTest {

    private final IndicatorService service = new IndicatorService();

    private List<DailyBar> rampBars(int n) {
        List<DailyBar> bars = new ArrayList<>(n);
        LocalDate start = LocalDate.of(2024, 1, 1);
        for (int i = 0; i < n; i++) {
            double close = i + 1.0;
            bars.add(DailyBar.builder()
                    .date(start.plusDays(i))
                    .open(close - 0.5)
                    .close(close)
                    .high(close + 0.5)
                    .low(close - 1.0)
                    .volume(1000 + i)
                    .build());
        }
        return bars;
    }

    @Test
    void maValuesMatchHandComputation() {
        AnalysisResult r = service.analyze(rampBars(80));
        assertEquals(78.0, r.getMa().get("ma5"));    // (76+77+78+79+80)/5
        assertEquals(70.5, r.getMa().get("ma20"));   // (61+..+80)/20
        assertEquals(50.5, r.getMa().get("ma60"));   // (21+..+80)/60
        assertEquals("2024-03-20", r.getLastDate()); // 2024-01-01 + 79 天
    }

    @Test
    void rsiAllUpIs100AndBollSymmetric() {
        AnalysisResult r = service.analyze(rampBars(80));
        assertEquals(100.0, r.getRsi());
        assertEquals(70.5, r.getBoll().get("mid"));
        // 总体标准差（ta4j StandardDeviationIndicator 口径）
        double var = 0;
        for (int x = 61; x <= 80; x++) {
            var += (x - 70.5) * (x - 70.5);
        }
        double std = Math.sqrt(var / 20);
        assertEquals(70.5 + 2 * std, r.getBoll().get("upper"), 0.01);
        assertEquals(70.5 - 2 * std, r.getBoll().get("lower"), 0.01);
        assertTrue(r.getBoll().get("upper") > r.getBoll().get("lower"));
    }

    @Test
    void changePctRoundedTwoDecimals() {
        AnalysisResult r = service.analyze(rampBars(80));
        assertEquals(1.27, r.getChangePct()); // (80-79)/79*100
    }

    @Test
    void upTrendEmitsExpectedSignals() {
        AnalysisResult r = service.analyze(rampBars(80));
        assertTrue(r.getSignals().contains("MA 多头排列（5>20>60）"));
        assertTrue(r.getSignals().contains("收盘价站上 MA20"));
        assertTrue(r.getSignals().contains("MACD 多头（DIF≥DEA）"));
        assertTrue(r.getSignals().stream().anyMatch(s -> s.contains("RSI14=100.0 超买区")));
        // 全上行无金叉死叉（金叉只在穿越时触发）
        assertFalse(r.getSignals().stream().anyMatch(s -> s.contains("MACD 金叉")));
    }

    @Test
    void shortHistoryOmitsUnavailableIndicators() {
        AnalysisResult r = service.analyze(rampBars(20));
        assertFalse(r.getMa().containsKey("ma60"));
        assertTrue(r.getMacd().isEmpty(), "不足 35 根不出 MACD");
        assertNull(r.getRsi() == null ? null : null); // rsi 存在（20>=14）
        assertTrue(r.getMa().containsKey("ma5"));
    }
}
