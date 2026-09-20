package com.zzh.stock_calculator.mcp.indicator;

import com.zzh.stock_calculator.mcp.quote.DailyBar;
import org.springframework.stereotype.Service;
import org.ta4j.core.Bar;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBar;
import org.ta4j.core.BaseBarSeriesBuilder;
import org.ta4j.core.Indicator;
import org.ta4j.core.indicators.EMAIndicator;
import org.ta4j.core.indicators.MACDIndicator;
import org.ta4j.core.indicators.RSIIndicator;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * ta4j 指标封装：MA(5/10/20/60) + MACD(12,26,9) + RSI14 + BOLL(20,2) + KDJ(9,3,3)。
 *
 * <p>KDJ 手工组合：RSV=StochasticOscillatorK(9)，K=SMA(RSV,3)，D=SMA(K,3)，J=3K-2D。
 * 信号只报最近 3 根内的金叉/死叉与极值区，输出面向 LLM 阅读。</p>
 */
@Service
public class IndicatorService {

    private static final int CROSS_WINDOW = 3;

    public AnalysisResult analyze(List<DailyBar> dailyBars) {
        BarSeries series = toSeries(dailyBars);
        int end = series.getEndIndex();
        ClosePriceIndicator close = new ClosePriceIndicator(series);

        Map<String, Double> ma = new LinkedHashMap<>();
        putIfEnough(ma, "ma5", new SMAIndicator(close, 5), 5, end);
        putIfEnough(ma, "ma10", new SMAIndicator(close, 10), 10, end);
        putIfEnough(ma, "ma20", new SMAIndicator(close, 20), 20, end);
        putIfEnough(ma, "ma60", new SMAIndicator(close, 60), 60, end);

        MACDIndicator macdLine = new MACDIndicator(close, 12, 26);
        EMAIndicator signalLine = new EMAIndicator(macdLine, 9);
        Map<String, Double> macd = new LinkedHashMap<>();
        if (end >= 34) {
            macd.put("dif", macdLine.getValue(end).doubleValue());
            macd.put("dea", signalLine.getValue(end).doubleValue());
            macd.put("hist", macdLine.getValue(end).doubleValue() - signalLine.getValue(end).doubleValue());
        }

        Double rsi = end >= 14 ? new RSIIndicator(close, 14).getValue(end).doubleValue() : null;

        Map<String, Double> boll = new LinkedHashMap<>();
        if (end >= 19) {
            // ta4j 无现成下轨类：BOLL(20,2) = SMA20 ± 2×20 期标准差
            SMAIndicator sma20 = new SMAIndicator(close, 20);
            StandardDeviationIndicator dev = new StandardDeviationIndicator(close, 20);
            double mid = sma20.getValue(end).doubleValue();
            double d = dev.getValue(end).doubleValue();
            boll.put("upper", round2(mid + 2 * d));
            boll.put("mid", round2(mid));
            boll.put("lower", round2(mid - 2 * d));
        }

        Map<String, Double> kdj = new LinkedHashMap<>();
        List<String> kdjSignals = new ArrayList<>();
        if (end >= 10) {
            StochasticOscillatorKIndicator rsv = new StochasticOscillatorKIndicator(series, 9);
            Indicator<Num> k = new SMAIndicator(rsv, 3);
            Indicator<Num> d = new SMAIndicator(k, 3);
            double kv = k.getValue(end).doubleValue();
            double dv = d.getValue(end).doubleValue();
            double jv = 3 * kv - 2 * dv;
            kdj.put("k", round2(kv));
            kdj.put("d", round2(dv));
            kdj.put("j", round2(jv));
            if (crossedUp(k, d, end)) {
                kdjSignals.add("KDJ 金叉（K 上穿 D，近 " + CROSS_WINDOW + " 根内）");
            } else if (crossedDown(k, d, end)) {
                kdjSignals.add("KDJ 死叉（K 下穿 D，近 " + CROSS_WINDOW + " 根内）");
            }
            if (jv > 100) {
                kdjSignals.add("J=" + round2(jv) + " 超买区");
            } else if (jv < 0) {
                kdjSignals.add("J=" + round2(jv) + " 超卖区");
            }
        }

        List<String> signals = new ArrayList<>(kdjSignals);
        collectMaSignals(close, end, ma, signals);
        collectMacdSignals(macdLine, signalLine, end, signals);
        if (rsi != null) {
            if (rsi > 70) {
                signals.add("RSI14=" + round2(rsi) + " 超买区（>70）");
            } else if (rsi < 30) {
                signals.add("RSI14=" + round2(rsi) + " 超卖区（<30）");
            }
        }

        double lastClose = dailyBars.get(dailyBars.size() - 1).getClose();
        double prevClose = dailyBars.size() >= 2
                ? dailyBars.get(dailyBars.size() - 2).getClose() : lastClose;
        return AnalysisResult.builder()
                .lastDate(dailyBars.get(dailyBars.size() - 1).getDate().toString())
                .lastClose(lastClose)
                .changePct(prevClose == 0 ? 0 : round2((lastClose - prevClose) / prevClose * 100))
                .ma(ma)
                .macd(macd)
                .rsi(rsi == null ? null : round2(rsi))
                .boll(boll)
                .kdj(kdj)
                .signals(signals)
                .build();
    }

    private BarSeries toSeries(List<DailyBar> dailyBars) {
        List<Bar> bars = new ArrayList<>(dailyBars.size());
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
        return new BaseBarSeriesBuilder().withName("daily").withBars(bars).build();
    }

    private void putIfEnough(Map<String, Double> map, String name, Indicator<Num> indicator, int barCount, int end) {
        if (end >= barCount - 1) {
            map.put(name, round2(indicator.getValue(end).doubleValue()));
        }
    }

    private void collectMaSignals(ClosePriceIndicator close, int end, Map<String, Double> ma,
                                  List<String> signals) {
        Double ma5 = ma.get("ma5");
        Double ma20 = ma.get("ma20");
        Double ma60 = ma.get("ma60");
        double last = close.getValue(end).doubleValue();
        if (ma5 != null && ma20 != null && ma60 != null) {
            if (ma5 > ma20 && ma20 > ma60) {
                signals.add("MA 多头排列（5>20>60）");
            } else if (ma5 < ma20 && ma20 < ma60) {
                signals.add("MA 空头排列（5<20<60）");
            }
        }
        if (ma20 != null) {
            signals.add(last >= ma20 ? "收盘价站上 MA20" : "收盘价位于 MA20 下方");
        }
    }

    private void collectMacdSignals(MACDIndicator dif, EMAIndicator dea, int end, List<String> signals) {
        if (end < 34) {
            return;
        }
        if (crossedUp(dif, dea, end)) {
            signals.add("MACD 金叉（DIF 上穿 DEA，近 " + CROSS_WINDOW + " 根内）");
        } else if (crossedDown(dif, dea, end)) {
            signals.add("MACD 死叉（DIF 下穿 DEA，近 " + CROSS_WINDOW + " 根内）");
        } else {
            signals.add(dif.getValue(end).doubleValue() >= dea.getValue(end).doubleValue()
                    ? "MACD 多头（DIF≥DEA）" : "MACD 空头（DIF<DEA）");
        }
    }

    /** 最近 CROSS_WINDOW 根内上穿判定 */
    private boolean crossedUp(Indicator<Num> a, Indicator<Num> b, int end) {
        for (int i = Math.max(1, end - CROSS_WINDOW + 1); i <= end; i++) {
            if (a.getValue(i).doubleValue() > b.getValue(i).doubleValue()
                    && a.getValue(i - 1).doubleValue() <= b.getValue(i - 1).doubleValue()) {
                return true;
            }
        }
        return false;
    }

    private boolean crossedDown(Indicator<Num> a, Indicator<Num> b, int end) {
        for (int i = Math.max(1, end - CROSS_WINDOW + 1); i <= end; i++) {
            if (a.getValue(i).doubleValue() < b.getValue(i).doubleValue()
                    && a.getValue(i - 1).doubleValue() >= b.getValue(i - 1).doubleValue()) {
                return true;
            }
        }
        return false;
    }

    private double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
