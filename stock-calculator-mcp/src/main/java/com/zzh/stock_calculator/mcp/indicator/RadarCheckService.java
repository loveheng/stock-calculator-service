package com.zzh.stock_calculator.mcp.indicator;

import com.zzh.stock_calculator.mcp.dict.StockDictEntry;
import com.zzh.stock_calculator.mcp.dict.StockDictMemoryService;
import com.zzh.stock_calculator.mcp.quote.DailyBar;
import com.zzh.stock_calculator.mcp.quote.QuoteSyncService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 雷达断言核心（单股 stock_radar_check 与批量 stock_radar_batch 共用）。
 * 输出布尔/枚举断言值——DAG switch 节点只做枚举路由不做表达式求值（§八底线），
 * 复合条件逻辑在工具侧收敛成 matched 列表与 allMatched 布尔。
 *
 * <p>条件 token 契约（SUPPORTED_CONDITIONS，与工具 description、ToolRegistry seed 三处同步）：
 * 缺省（不传 conditions）保持旧行为——只算 ma_break + volume_surge 并输出 signal 枚举
 * （both/break_only/volume_only/none，switch 分流兼容）；显式传 conditions 时在 signal 之外
 * 追加逐条件布尔 results / 命中列表 matched / allMatched。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RadarCheckService {

    public static final List<String> SUPPORTED_CONDITIONS = List.of(
            "ma_break", "volume_surge",
            "macd_golden", "macd_dead", "kdj_golden", "kdj_dead", "kdj_overbought", "kdj_oversold",
            "pct_up", "pct_down",
            "support_break", "resistance_break");

    /** MACD(12,26,9) 完整计算需 end>=34 */
    private static final int MIN_MACD_BARS = 36;
    /** KDJ(9,3,3) 需 10 根，留 1 根余量 */
    private static final int MIN_KDJ_BARS = 11;
    /** 位带基线取前 n-1 根，SupportResistanceService.compute 至少 20 根 */
    private static final int MIN_LEVELS_BARS = 22;
    private static final int DEFAULT_PCT = 3;

    private final StockDictMemoryService dictService;
    private final QuoteSyncService quoteSyncService;
    private final IndicatorService indicatorService;
    private final SupportResistanceService levelsService;

    /**
     * 单股雷达断言。conditions 为空 = 旧行为；含未支持 token 返回 error（带可用清单，LLM 自纠重发）。
     */
    public Map<String, Object> check(String stock, Integer maWindow, Double volumeRatio,
                                     List<String> conditions, Double pctThreshold) {
        Optional<StockDictEntry> entry = dictService.resolve(stock);
        if (entry.isEmpty()) {
            return Map.of("signal", "none", "error", "无法解析股票: " + stock);
        }
        // 参数边界钳制（系统边界校验）：窗口 5-120（过小噪声大、过大无意义），倍数 (0, 100]，涨跌幅 (0, 20]
        int window = maWindow == null ? 20 : Math.min(Math.max(maWindow, 5), 120);
        double ratioThreshold = volumeRatio == null || volumeRatio <= 0 ? 2.0 : Math.min(volumeRatio, 100);
        double pctThr = pctThreshold == null || pctThreshold <= 0 ? DEFAULT_PCT : Math.min(pctThreshold, 20);
        List<String> requested = normalize(conditions);
        if (requested == null) {
            return Map.of("signal", "none", "error", "conditions 含未支持条件，可用: "
                    + String.join("/", SUPPORTED_CONDITIONS));
        }
        boolean legacyMode = requested.isEmpty();
        boolean needMacd = requested.contains("macd_golden") || requested.contains("macd_dead");
        boolean needKdj = requested.contains("kdj_golden") || requested.contains("kdj_dead")
                || requested.contains("kdj_overbought") || requested.contains("kdj_oversold");
        boolean needIndicators = needMacd || needKdj;
        boolean needLevels = requested.contains("support_break") || requested.contains("resistance_break");

        String stockId = entry.get().getStockId();
        int minBars = maxOf(window + 1,
                needMacd ? MIN_MACD_BARS : 0,
                needKdj ? MIN_KDJ_BARS : 0,
                needLevels ? MIN_LEVELS_BARS : 0);
        int fetchBars = needLevels ? 130 : Math.max(minBars, window + 10);
        List<DailyBar> bars = quoteSyncService.ensureBars(stockId, fetchBars);
        if (bars.size() < minBars) {
            return Map.of("signal", "none", "dataInsufficient", true,
                    "stockId", stockId, "bars", bars.size());
        }
        DailyBar last = bars.get(bars.size() - 1);
        double ma = bars.subList(bars.size() - window - 1, bars.size() - 1).stream()
                .mapToDouble(DailyBar::getClose).average().orElse(0);
        boolean maBreak = last.getClose() > ma && bars.get(bars.size() - 2).getClose() <= ma;
        double prev5AvgVolume = bars.subList(bars.size() - 6, bars.size() - 1).stream()
                .mapToDouble(DailyBar::getVolume).average().orElse(0);
        double volumeMultiple = prev5AvgVolume > 0 ? last.getVolume() / prev5AvgVolume : 0;
        boolean volumeSurged = volumeMultiple >= ratioThreshold;
        String signal = maBreak && volumeSurged ? "both"
                : maBreak ? "break_only"
                : volumeSurged ? "volume_only" : "none";

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("stockId", stockId);
        out.put("name", entry.get().getName());
        out.put("signal", signal);
        out.put("breakAboveMa", maBreak);
        out.put("maWindow", window);
        out.put("volumeMultiple", round2(volumeMultiple));
        out.put("volumeRatioThreshold", ratioThreshold);
        out.put("ma", round2(ma));
        out.put("lastClose", round2(last.getClose()));
        if (legacyMode) {
            // 旧行为也补 matched/allMatched（批量过筛统一按命中列表汇总），signal 枚举语义不变
            List<String> legacyMatched = maBreak && volumeSurged ? List.of("ma_break", "volume_surge")
                    : maBreak ? List.of("ma_break")
                    : volumeSurged ? List.of("volume_surge") : List.of();
            out.put("matched", legacyMatched);
            out.put("allMatched", legacyMatched.size() == 2);
            return out;
        }

        AnalysisResult analysis = needIndicators ? indicatorService.analyze(bars) : null;
        double prevClose = bars.get(bars.size() - 2).getClose();
        double changePct = prevClose == 0 ? 0 : round2((last.getClose() - prevClose) / prevClose * 100);
        LevelsResult levels = needLevels
                ? levelsService.compute(bars.subList(0, bars.size() - 1)) : null;

        Map<String, Boolean> results = new LinkedHashMap<>();
        for (String cond : requested) {
            results.put(cond, evaluate(cond, maBreak, volumeSurged, changePct, pctThr, analysis, levels, last.getClose()));
        }
        out.put("conditions", requested);
        out.put("results", results);
        out.put("matched", results.entrySet().stream()
                .filter(Map.Entry::getValue).map(Map.Entry::getKey).toList());
        out.put("allMatched", results.values().stream().allMatch(Boolean::booleanValue));
        out.put("changePct", changePct);
        out.put("pctThreshold", pctThr);
        if (needIndicators) {
            out.put("macd", analysis.getMacd());
            out.put("kdj", analysis.getKdj());
            out.put("indicatorSignals", analysis.getSignals());
        }
        if (needLevels) {
            // 位带按昨收分类（基线=前 n-1 根）；今日收盘穿越最近带中心即命中，穿越后角色互换
            out.put("levelsNote", "位带按昨收分类，今日收盘穿越最近带中心即命中");
            Map<String, Object> levelBreak = levelBreakDetail(results, levels);
            if (!levelBreak.isEmpty()) {
                out.put("levelBreak", levelBreak);
            }
        }
        return out;
    }

    private boolean evaluate(String cond, boolean maBreak, boolean volumeSurged, double changePct, double pctThr,
                             AnalysisResult analysis, LevelsResult levels, double lastClose) {
        return switch (cond) {
            case "ma_break" -> maBreak;
            case "volume_surge" -> volumeSurged;
            case "macd_golden" -> analysis != null && hasSignal(analysis, "MACD 金叉");
            case "macd_dead" -> analysis != null && hasSignal(analysis, "MACD 死叉");
            case "kdj_golden" -> analysis != null && hasSignal(analysis, "KDJ 金叉");
            case "kdj_dead" -> analysis != null && hasSignal(analysis, "KDJ 死叉");
            case "kdj_overbought" -> analysis != null && analysis.getKdj() != null
                    && analysis.getKdj().get("j") != null && analysis.getKdj().get("j") > 100;
            case "kdj_oversold" -> analysis != null && analysis.getKdj() != null
                    && analysis.getKdj().get("j") != null && analysis.getKdj().get("j") < 0;
            case "pct_up" -> changePct >= pctThr;
            case "pct_down" -> changePct <= -pctThr;
            case "resistance_break" -> crossedNearest(levels != null ? levels.getResistances() : null, lastClose, true);
            case "support_break" -> crossedNearest(levels != null ? levels.getSupports() : null, lastClose, false);
            default -> false;
        };
    }

    private boolean hasSignal(AnalysisResult analysis, String prefix) {
        return analysis.getSignals().stream().anyMatch(s -> s.startsWith(prefix));
    }

    /** 最近位带（按昨收由近及远排首位）：今日收盘穿越其带中心即命中 */
    private boolean crossedNearest(List<PriceLevel> nearestFirst, double lastClose, boolean upward) {
        if (nearestFirst == null || nearestFirst.isEmpty()) {
            return false;
        }
        PriceLevel nearest = nearestFirst.get(0);
        double center = (nearest.getPriceLow() + nearest.getPriceHigh()) / 2;
        return upward ? lastClose >= center : lastClose <= center;
    }

    private Map<String, Object> levelBreakDetail(Map<String, Boolean> results, LevelsResult levels) {
        boolean up = Boolean.TRUE.equals(results.get("resistance_break"));
        boolean down = Boolean.TRUE.equals(results.get("support_break"));
        List<PriceLevel> side = up ? levels.getResistances() : down ? levels.getSupports() : null;
        if (side == null || side.isEmpty()) {
            return Map.of();
        }
        PriceLevel nearest = side.get(0);
        return Map.of(
                "direction", up ? "resistance" : "support",
                "priceLow", nearest.getPriceLow(),
                "priceHigh", nearest.getPriceHigh(),
                "type", nearest.getType(),
                "note", nearest.getNote());
    }

    /** null/空 → 旧行为空列表；trim+小写归一；含未支持 token → null（error 信号） */
    private List<String> normalize(List<String> conditions) {
        if (conditions == null || conditions.isEmpty()) {
            return List.of();
        }
        Set<String> seen = new LinkedHashSet<>();
        for (String c : conditions) {
            if (c == null || c.isBlank()) {
                continue;
            }
            String token = c.trim().toLowerCase();
            if (!SUPPORTED_CONDITIONS.contains(token)) {
                return null;
            }
            seen.add(token);
        }
        return List.copyOf(seen);
    }

    private int maxOf(int... values) {
        int max = values[0];
        for (int v : values) {
            max = Math.max(max, v);
        }
        return max;
    }

    private double round2(double v) {
        return Math.round(v * 100) / 100.0;
    }
}
