package com.zzh.stock_calculator.mcp.indicator;

import com.zzh.stock_calculator.mcp.quote.DailyBar;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.TreeMap;

/**
 * 支撑/压力位计算（三法合一，全部基于日线 OHLCV）：
 *
 * <ul>
 *   <li>枢轴点 Pivot：昨根 H/L/C 经典公式（P/R1/S1/R2/S2），短线参考位；</li>
 *   <li>摆动高低点聚类：近 {@value SWING_NEIGHBORS}*2+1 根窗口的严格局部极值，
 *       相邻 1.5% 内聚类成带，触及次数越多越强（经典形态分析做法）；</li>
 *   <li>近似成交量密集区：按价格分桶把每根 K 线 volume 均匀摊到其 high-low 区间
 *       （Volume Profile 的日线近似，非 tick 级），高堆积带为强支撑/压力。</li>
 * </ul>
 *
 * <p>分类规则：位带中心在现价上方为压力（由近及远），下方为支撑（由近及远）。
 * 日线级输出是「位带」而非精确点位，配合趋势背景使用。</p>
 */
@Service
public class SupportResistanceService {

    private static final int SWING_NEIGHBORS = 3;
    private static final double CLUSTER_PCT = 0.015;
    private static final int LOOKBACK = 120;
    private static final int VOLUME_BUCKETS = 30;
    private static final double HVN_MEAN_MULT = 1.8;
    private static final int MAX_LEVELS_PER_SIDE = 5;

    public LevelsResult compute(List<DailyBar> bars) {
        if (bars.size() < 20) {
            throw new IllegalArgumentException("支撑压力计算至少需要 20 根日线, 实际 " + bars.size());
        }
        double lastClose = bars.get(bars.size() - 1).getClose();
        List<PriceLevel> candidates = new ArrayList<>();
        candidates.addAll(pivotLevels(bars.get(bars.size() - 1), lastClose));
        candidates.addAll(swingLevels(bars, lastClose));
        candidates.addAll(volumeLevels(bars, lastClose));

        // 现价上方 = 压力（由近及远）；下方 = 支撑（由近及远）；带中心分类
        List<PriceLevel> resistances = candidates.stream()
                .filter(l -> (l.getPriceLow() + l.getPriceHigh()) / 2 >= lastClose)
                .sorted(Comparator.comparingDouble(l -> (l.getPriceLow() + l.getPriceHigh()) / 2))
                .limit(MAX_LEVELS_PER_SIDE)
                .toList();
        List<PriceLevel> supports = candidates.stream()
                .filter(l -> (l.getPriceLow() + l.getPriceHigh()) / 2 < lastClose)
                .sorted(Comparator.comparingDouble((PriceLevel l) -> (l.getPriceLow() + l.getPriceHigh()) / 2).reversed())
                .limit(MAX_LEVELS_PER_SIDE)
                .toList();

        return LevelsResult.builder()
                .lastDate(bars.get(bars.size() - 1).getDate().toString())
                .lastClose(lastClose)
                .resistances(resistances)
                .supports(supports)
                .build();
    }

    /** 昨根经典枢轴点：P=(H+L+C)/3, R1=2P-L, S1=2P-H, R2=P+(H-L), S2=P-(H-L) */
    private List<PriceLevel> pivotLevels(DailyBar last, double lastClose) {
        double h = last.getHigh(), l = last.getLow(), c = last.getClose();
        double p = (h + l + c) / 3;
        List<double[]> pivots = List.of(
                new double[]{2 * p - l, 2 * p - l}, new double[]{p + (h - l), p + (h - l)},
                new double[]{p, p}, new double[]{p - (h - l), p - (h - l)}, new double[]{2 * p - h, 2 * p - h});
        List<PriceLevel> out = new ArrayList<>();
        java.util.Set<Double> seen = new java.util.HashSet<>();
        for (double[] pv : pivots) {
            // 平盘日 P/R1/S1/R2/S2 可能同价，去重避免挤占位带名额
            if (!seen.add(round2(pv[0]))) {
                continue;
            }
            out.add(PriceLevel.builder()
                    .priceLow(round2(pv[0])).priceHigh(round2(pv[1]))
                    .type("pivot").touches(1)
                    .distPct(distPct((pv[0] + pv[1]) / 2, lastClose))
                    .note("昨根枢轴点（日线级别短线参考）")
                    .build());
        }
        return out;
    }

    /** 近 LOOKBACK 根的严格局部极值（左右各 SWING_NEIGHBORS 根确认），1.5% 阈值聚类 */
    private List<PriceLevel> swingLevels(List<DailyBar> bars, double lastClose) {
        int n = bars.size();
        int from = Math.max(SWING_NEIGHBORS, n - LOOKBACK);
        int to = n - SWING_NEIGHBORS; // 末段未确认的不算
        List<Double> swingHighs = new ArrayList<>();
        List<Double> swingLows = new ArrayList<>();
        for (int i = from; i < to; i++) {
            if (isExtreme(bars, i, SWING_NEIGHBORS, true)) {
                swingHighs.add(bars.get(i).getHigh());
            }
            if (isExtreme(bars, i, SWING_NEIGHBORS, false)) {
                swingLows.add(bars.get(i).getLow());
            }
        }
        List<PriceLevel> out = new ArrayList<>();
        out.addAll(cluster(bars, swingHighs, true, lastClose));
        out.addAll(cluster(bars, swingLows, false, lastClose));
        return out;
    }

    private boolean isExtreme(List<DailyBar> bars, int i, int k, boolean high) {
        double v = high ? bars.get(i).getHigh() : bars.get(i).getLow();
        for (int j = i - k; j <= i + k; j++) {
            if (j == i) {
                continue;
            }
            double other = high ? bars.get(j).getHigh() : bars.get(j).getLow();
            if (high ? other >= v : other <= v) {
                return false; // 严格极值：平顶不算
            }
        }
        return true;
    }

    /** 价位差在现价 1.5% 内的极值聚类为位带，band=[min,max]，touches=成员数 */
    private List<PriceLevel> cluster(List<DailyBar> bars, List<Double> prices, boolean highs, double lastClose) {
        if (prices.isEmpty()) {
            return List.of();
        }
        List<Double> sorted = new ArrayList<>(prices);
        sorted.sort(Comparator.naturalOrder());
        List<PriceLevel> out = new ArrayList<>();
        double threshold = lastClose * CLUSTER_PCT;
        List<Double> group = new ArrayList<>();
        group.add(sorted.get(0));
        for (int i = 1; i <= sorted.size(); i++) {
            Double next = i < sorted.size() ? sorted.get(i) : null;
            if (next != null && next - group.get(group.size() - 1) <= threshold) {
                group.add(next);
                continue;
            }
            // 结算当前组
            double lo = group.get(0), hi = group.get(group.size() - 1);
            int touches = group.size();
            out.add(PriceLevel.builder()
                    .priceLow(round2(lo)).priceHigh(round2(hi))
                    .type("swing").touches(touches)
                    .distPct(distPct((lo + hi) / 2, lastClose))
                    .note((highs ? highs(bars, lo, hi) : lows(bars, lo, hi)) + (touches >= 2 ? "，" + touches + " 次触及" : ""))
                    .build());
            group = new ArrayList<>();
            if (next != null) {
                group.add(next);
            }
        }
        return out;
    }

    private String highs(List<DailyBar> bars, double lo, double hi) {
        long recent = bars.stream().filter(b -> b.getDate().isAfter(bars.get(bars.size() - 1).getDate().minusDays(60)))
                .filter(b -> b.getHigh() >= lo && b.getHigh() <= hi).count();
        return "摆动高点带" + (recent > 0 ? "（近 60 日内 " + recent + " 根触及）" : "");
    }

    private String lows(List<DailyBar> bars, double lo, double hi) {
        long recent = bars.stream().filter(b -> b.getDate().isAfter(bars.get(bars.size() - 1).getDate().minusDays(60)))
                .filter(b -> b.getLow() >= lo && b.getLow() <= hi).count();
        return "摆动低点带" + (recent > 0 ? "（近 60 日内 " + recent + " 根触及）" : "");
    }

    /** 近 LOOKBACK 根近似 Volume Profile：volume 均匀摊到每根 K 线的 high-low 覆盖桶 */
    private List<PriceLevel> volumeLevels(List<DailyBar> bars, double lastClose) {
        List<DailyBar> window = bars.subList(Math.max(0, bars.size() - LOOKBACK), bars.size());
        double priceMin = window.stream().mapToDouble(DailyBar::getLow).min().orElse(0);
        double priceMax = window.stream().mapToDouble(DailyBar::getHigh).max().orElse(0);
        if (priceMax <= priceMin) {
            return List.of();
        }
        double bucketHeight = (priceMax - priceMin) / VOLUME_BUCKETS;
        TreeMap<Integer, Double> buckets = new TreeMap<>();
        for (DailyBar b : window) {
            int from = (int) Math.floor((b.getLow() - priceMin) / bucketHeight);
            int to = (int) Math.floor((b.getHigh() - priceMin) / bucketHeight);
            from = Math.max(0, Math.min(from, VOLUME_BUCKETS - 1));
            to = Math.max(0, Math.min(to, VOLUME_BUCKETS - 1));
            int span = to - from + 1;
            double per = b.getVolume() / span;
            for (int idx = from; idx <= to; idx++) {
                buckets.merge(idx, per, Double::sum);
            }
        }
        double total = buckets.values().stream().mapToDouble(Double::doubleValue).sum();
        double mean = total / VOLUME_BUCKETS;
        // 高于均值 HVN_MEAN_MULT 倍的桶为高成交密集，相邻合并
        List<int[]> hvn = new ArrayList<>();
        int start = -1;
        for (int i = 0; i < VOLUME_BUCKETS; i++) {
            boolean hot = buckets.getOrDefault(i, 0.0) >= mean * HVN_MEAN_MULT;
            if (hot && start < 0) {
                start = i;
            }
            if ((!hot || i == VOLUME_BUCKETS - 1) && start >= 0) {
                int end = hot ? i : i - 1;
                hvn.add(new int[]{start, end});
                start = -1;
            }
        }
        double finalTotal = total;
        return hvn.stream()
                .sorted(Comparator.comparingDouble((int[] r) ->
                        buckets.subMap(r[0], true, r[1], true).values().stream().mapToDouble(Double::doubleValue).sum()).reversed())
                .limit(3)
                .map(r -> {
                    double lo = priceMin + r[0] * bucketHeight;
                    double hi = priceMin + (r[1] + 1) * bucketHeight;
                    double share = buckets.subMap(r[0], true, r[1], true).values().stream()
                            .mapToDouble(Double::doubleValue).sum() / finalTotal * 100;
                    return PriceLevel.builder()
                            .priceLow(round2(lo)).priceHigh(round2(hi))
                            .type("volume").touches(1)
                            .volumeShare(round2(share))
                            .distPct(distPct((lo + hi) / 2, lastClose))
                            .note("成交密集区（近 " + window.size() + " 根 " + round2(share) + "% 成交量堆积）")
                            .build();
                })
                .toList();
    }

    private double distPct(double price, double lastClose) {
        return lastClose == 0 ? 0 : round2((price - lastClose) / lastClose * 100);
    }

    private double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
