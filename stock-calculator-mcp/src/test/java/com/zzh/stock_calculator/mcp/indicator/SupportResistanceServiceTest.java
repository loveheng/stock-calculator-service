package com.zzh.stock_calculator.mcp.indicator;

import com.zzh.stock_calculator.mcp.quote.DailyBar;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SupportResistanceServiceTest {

    private final SupportResistanceService service = new SupportResistanceService();

    private DailyBar bar(LocalDate date, double open, double close, double high, double low, double volume) {
        return DailyBar.builder().date(date).open(open).close(close).high(high).low(low).volume(volume).build();
    }

    @Test
    void pivotFormulaMatchesHandComputation() {
        // 19 根平盘（高15.5/低14.5/收15），末根 H=20 L=10 C=15
        List<DailyBar> bars = new ArrayList<>();
        LocalDate d = LocalDate.of(2024, 1, 1);
        for (int i = 0; i < 19; i++) {
            bars.add(bar(d.plusDays(i), 15, 15, 15.5, 14.5, 1000));
        }
        bars.add(bar(d.plusDays(19), 15, 15, 20, 10, 1000));

        LevelsResult r = service.compute(bars);
        assertEquals(15.0, r.getLastClose());
        // P=(20+10+15)/3=15, R1=2P-L=20, S1=2P-H=10, R2=P+(H-L)=25, S2=P-(H-L)=5
        List<Double> res = r.getResistances().stream().filter(l -> l.getType().equals("pivot"))
                .map(PriceLevel::getPriceLow).toList();
        List<Double> sup = r.getSupports().stream().filter(l -> l.getType().equals("pivot"))
                .map(PriceLevel::getPriceLow).toList();
        assertEquals(List.of(15.0, 20.0, 25.0), res, "压力侧枢轴由近及远");
        assertEquals(List.of(10.0, 5.0), sup, "支撑侧枢轴由近及远");
    }

    @Test
    void swingHighAndLowDetectedAndClassified() {
        // 40 根平盘 100；第 6 根 spike high=110，第 20 根 spike low=90
        List<DailyBar> bars = new ArrayList<>();
        LocalDate d = LocalDate.of(2024, 1, 1);
        for (int i = 0; i < 40; i++) {
            double high = i == 6 ? 110 : 100;
            double low = i == 20 ? 90 : 100;
            bars.add(bar(d.plusDays(i), 100, 100, high, low, 1000));
        }
        LevelsResult r = service.compute(bars);
        assertEquals(100.0, r.getLastClose());
        assertTrue(r.getResistances().stream().anyMatch(l ->
                        l.getType().equals("swing") && l.getPriceLow() == 110.0 && l.getPriceHigh() == 110.0),
                "swing high 110 应为压力带: " + r.getResistances());
        assertTrue(r.getSupports().stream().anyMatch(l ->
                        l.getType().equals("swing") && l.getPriceLow() == 90.0),
                "swing low 90 应为支撑带: " + r.getSupports());
    }

    @Test
    void volumeProfileFindsHeavyNode() {
        // 20 根 99-101 薄量 + 10 根 108-112 厚量 → HVN 应落在 108-112 带
        List<DailyBar> bars = new ArrayList<>();
        LocalDate d = LocalDate.of(2024, 1, 1);
        for (int i = 0; i < 20; i++) {
            bars.add(bar(d.plusDays(i), 100, 100, 101, 99, 100));
        }
        for (int i = 20; i < 30; i++) {
            bars.add(bar(d.plusDays(i), 110, i == 29 ? 111.5 : 110, 112, 108, 1000));
        }
        LevelsResult r = service.compute(bars);
        List<PriceLevel> all = new ArrayList<>(r.getResistances());
        all.addAll(r.getSupports());
        PriceLevel hvn = all.stream().filter(l -> l.getType().equals("volume")).findFirst().orElse(null);
        assertTrue(hvn != null, "应识别出成交密集区");
        assertTrue(hvn.getPriceLow() >= 107.5 && hvn.getPriceHigh() <= 112.1,
                "HVN 带应落在厚量区: " + hvn);
        assertTrue(hvn.getVolumeShare() > 70, "厚量区占比应显著: " + hvn.getVolumeShare());
    }

    @Test
    void insufficientBarsThrows() {
        List<DailyBar> bars = new ArrayList<>();
        LocalDate d = LocalDate.of(2024, 1, 1);
        for (int i = 0; i < 10; i++) {
            bars.add(bar(d.plusDays(i), 100, 100, 100.5, 99.5, 1000));
        }
        try {
            service.compute(bars);
            org.junit.jupiter.api.Assertions.fail("应抛异常");
        } catch (IllegalArgumentException expected) {
            // 预期
        }
    }
}
