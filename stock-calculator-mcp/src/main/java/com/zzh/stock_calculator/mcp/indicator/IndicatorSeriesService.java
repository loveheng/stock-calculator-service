package com.zzh.stock_calculator.mcp.indicator;

import com.zzh.stock_calculator.mcp.quote.DailyBar;
import org.springframework.stereotype.Service;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBar;
import org.ta4j.core.BaseBarSeriesBuilder;
import org.ta4j.core.Indicator;
import org.ta4j.core.indicators.EMAIndicator;
import org.ta4j.core.indicators.MACDIndicator;
import org.ta4j.core.indicators.SMAIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.indicators.statistics.StandardDeviationIndicator;
import org.ta4j.core.indicators.StochasticOscillatorKIndicator;
import org.ta4j.core.num.DoubleNum;
import org.ta4j.core.num.Num;

import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 指标序列计算（free-canvas §3.1 无状态 compute 底座，路径 A 纯计算 + 本域只读，禁外部 IO）：
 * 逐根输出数组与输入 klines 一一对齐，暖机期槽位 null 占位（不截短）；
 * 根数不足 minBars 时该指标整体返回 null（前端按 null 跳过渲染）。
 * <p>暖机口径：macd=33（EMA26+信号 9）、kdj=10（RSV9+K3+D3）、boll=20（SMA20±2σ），
 * 与 main 能力端点 minBars 配置同源对齐。</p>
 */
@Service
public class IndicatorSeriesService {

    /** 白名单（与 main 能力端点清单一致；白名单外交由 main 400 拦截，工具层兜底拒绝） */
    public static final List<String> WHITELIST = List.of("macd", "kdj", "boll");

    private static final int MACD_WARMUP = 33;
    private static final int KDJ_WARMUP = 10;
    private static final int BOLL_WARMUP = 20;

    /** 计算指定指标序列；返回 indicator 名 → {子键 → 对齐数组}；未知指标抛 IllegalArgumentException */
    public Map<String, Object> compute(List<DailyBar> bars, List<String> names) {
        BarSeries series = toSeries(bars);
        int end = series.getEndIndex();
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        for (String name : names) {
            switch (name) {
                case "macd" -> out.put("macd", macdArrays(series, end));
                case "kdj" -> out.put("kdj", kdjArrays(series, end));
                case "boll" -> out.put("boll", bollArrays(series, end));
                default -> throw new IllegalArgumentException("未知指标: " + name);
            }
        }
        return out;
    }

    /** MACD(12,26,9)：macd=DIF / signal=DEA / hist=DIF-DEA */
    private Object macdArrays(BarSeries series, int end) {
        ClosePriceIndicator close = new ClosePriceIndicator(series);
        MACDIndicator dif = new MACDIndicator(close, 12, 26);
        EMAIndicator dea = new EMAIndicator(dif, 9);
        if (end < MACD_WARMUP - 1) {
            return null;
        }
        List<Double> macd = new ArrayList<>(end + 1);
        List<Double> signal = new ArrayList<>(end + 1);
        List<Double> hist = new ArrayList<>(end + 1);
        for (int i = 0; i <= end; i++) {
            if (i < MACD_WARMUP - 1) {
                macd.add(null);
                signal.add(null);
                hist.add(null);
                continue;
            }
            double d = dif.getValue(i).doubleValue();
            double s = dea.getValue(i).doubleValue();
            macd.add(round4(d));
            signal.add(round4(s));
            hist.add(round4(d - s));
        }
        return Map.of("macd", macd, "signal", signal, "hist", hist);
    }

    /** KDJ(9,3,3)：K=SMA(RSV,3)、D=SMA(K,3)、J=3K-2D */
    private Object kdjArrays(BarSeries series, int end) {
        StochasticOscillatorKIndicator rsv = new StochasticOscillatorKIndicator(series, 9);
        Indicator<Num> k = new SMAIndicator(rsv, 3);
        Indicator<Num> d = new SMAIndicator(k, 3);
        if (end < KDJ_WARMUP - 1) {
            return null;
        }
        List<Double> ks = new ArrayList<>(end + 1);
        List<Double> ds = new ArrayList<>(end + 1);
        List<Double> js = new ArrayList<>(end + 1);
        for (int i = 0; i <= end; i++) {
            if (i < KDJ_WARMUP - 1) {
                ks.add(null);
                ds.add(null);
                js.add(null);
                continue;
            }
            double kv = k.getValue(i).doubleValue();
            double dv = d.getValue(i).doubleValue();
            ks.add(round4(kv));
            ds.add(round4(dv));
            js.add(round4(3 * kv - 2 * dv));
        }
        return Map.of("k", ks, "d", ds, "j", js);
    }

    /** BOLL(20,2)：mid=SMA20、upper/lower=mid±2σ（ta4j 无现成下轨类，同 IndicatorService 口径） */
    private Object bollArrays(BarSeries series, int end) {
        ClosePriceIndicator close = new ClosePriceIndicator(series);
        SMAIndicator sma20 = new SMAIndicator(close, 20);
        StandardDeviationIndicator dev = new StandardDeviationIndicator(close, 20);
        if (end < BOLL_WARMUP - 1) {
            return null;
        }
        List<Double> upper = new ArrayList<>(end + 1);
        List<Double> mid = new ArrayList<>(end + 1);
        List<Double> lower = new ArrayList<>(end + 1);
        for (int i = 0; i <= end; i++) {
            if (i < BOLL_WARMUP - 1) {
                upper.add(null);
                mid.add(null);
                lower.add(null);
                continue;
            }
            double m = sma20.getValue(i).doubleValue();
            double s = dev.getValue(i).doubleValue();
            upper.add(round4(m + 2 * s));
            mid.add(round4(m));
            lower.add(round4(m - 2 * s));
        }
        return Map.of("upper", upper, "mid", mid, "lower", lower);
    }

    private BarSeries toSeries(List<DailyBar> dailyBars) {
        List<org.ta4j.core.Bar> bars = new ArrayList<>(dailyBars.size());
        for (DailyBar b : dailyBars) {
            bars.add(BaseBar.builder()
                    .timePeriod(Duration.ofDays(1))
                    .endTime(b.getDate().atStartOfDay(ZoneOffset.UTC))
                    .openPrice(DoubleNum.valueOf(b.getOpen()))
                    .closePrice(DoubleNum.valueOf(b.getClose()))
                    .highPrice(DoubleNum.valueOf(b.getHigh()))
                    .lowPrice(DoubleNum.valueOf(b.getLow()))
                    .volume(DoubleNum.valueOf(b.getVolume()))
                    .build());
        }
        return new BaseBarSeriesBuilder().withName("compute").withBars(bars).build();
    }

    private double round4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }
}
