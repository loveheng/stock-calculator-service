package com.zzh.stock_calculator.mcp;

import com.zzh.stock_calculator.mcp.dict.StockDictEntry;
import com.zzh.stock_calculator.mcp.dict.StockDictMemoryService;
import com.zzh.stock_calculator.mcp.indicator.IndicatorService;
import com.zzh.stock_calculator.mcp.indicator.RadarCheckService;
import com.zzh.stock_calculator.mcp.indicator.SupportResistanceService;
import com.zzh.stock_calculator.mcp.quote.DailyBar;
import com.zzh.stock_calculator.mcp.quote.QuoteSyncService;
import com.zzh.stock_calculator.mcp.tool.StockRadarBatchTool;
import com.zzh.stock_calculator.mcp.tool.StockRadarCheckTool;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 雷达断言工具单测（纯内存：dict/quotes mock，指标与位带真实计算）。
 * 旧四信号分支保持兼容（signal 枚举不变）；conditions 扩展覆盖 pct_up / kdj_oversold /
 * resistance_break / 未知 token / 数据不足；批量工具覆盖命中汇总与 unresolved。
 * 关键不变量：bar 构造保证 MA20 与量能条件的组合覆盖（前 20 根低位收盘、当日价量可控）。
 */
class StockRadarCheckToolTest {

    private final StockDictMemoryService dict = Mockito.mock(StockDictMemoryService.class);
    private final QuoteSyncService quotes = Mockito.mock(QuoteSyncService.class);
    private final RadarCheckService radarService = new RadarCheckService(
            dict, quotes, new IndicatorService(), new SupportResistanceService());
    private final StockRadarCheckTool tool = new StockRadarCheckTool(radarService);
    private final StockRadarBatchTool batchTool = new StockRadarBatchTool(radarService);

    private void stubDict(String code) {
        StockDictEntry entry = new StockDictEntry();
        entry.setStockId(code);
        entry.setName("贵州茅台");
        when(dict.resolve(code)).thenReturn(Optional.of(entry));
    }

    /** 前 n-1 根收盘 10（带 0.1 影线），前 5 根量 100，当日可调 */
    private List<DailyBar> bars(double lastClose, double lastVolume) {
        return bars(lastClose, lastVolume, 25);
    }

    private List<DailyBar> bars(double lastClose, double lastVolume, int n) {
        List<DailyBar> list = new ArrayList<>();
        for (int i = 0; i < n - 1; i++) {
            list.add(bar(i, 10, 10.1, 9.9, 100));
        }
        list.add(bar(n - 1, lastClose, lastClose + 0.1, lastClose - 0.1, lastVolume));
        return list;
    }

    private DailyBar bar(int dayOffset, double close, double high, double low) {
        return bar(dayOffset, close, high, low, 100);
    }

    private DailyBar bar(int dayOffset, double close, double high, double low, double volume) {
        return DailyBar.builder()
                .date(LocalDate.of(2026, 6, 1).plusDays(dayOffset))
                .close(close).open(close).high(high).low(low).volume(volume).build();
    }

    private void stubBars(double lastClose, double lastVolume) {
        stubDict("600519");
        when(quotes.ensureBars(anyString(), anyInt())).thenReturn(bars(lastClose, lastVolume));
    }

    // ========== 旧行为：signal 枚举兼容（不传 conditions） ==========

    @Test
    void 破线且量增_both() {
        stubBars(10.5, 300);
        Map<String, Object> out = tool.radarCheck("600519", null, null, null, null);
        assertThat(out.get("signal")).isEqualTo("both");
        assertThat((Boolean) out.get("breakAboveMa")).isTrue();
        assertThat((Double) out.get("volumeMultiple")).isEqualTo(3.0);
        assertThat(out.get("matched")).isEqualTo(List.of("ma_break", "volume_surge"));
        assertThat((Boolean) out.get("allMatched")).isTrue();
    }

    @Test
    void 只破线_volume不足_breakOnly() {
        stubBars(10.5, 100);
        Map<String, Object> out = tool.radarCheck("600519", null, null, null, null);
        assertThat(out.get("signal")).isEqualTo("break_only");
    }

    @Test
    void 只量增_未破线_volumeOnly() {
        stubBars(9.5, 300);
        Map<String, Object> out = tool.radarCheck("600519", null, null, null, null);
        assertThat(out.get("signal")).isEqualTo("volume_only");
    }

    @Test
    void 都不满足_none() {
        stubBars(9.5, 100);
        Map<String, Object> out = tool.radarCheck("600519", null, null, null, null);
        assertThat(out.get("signal")).isEqualTo("none");
        assertThat((Boolean) out.get("allMatched")).isFalse();
    }

    @Test
    void 日线不足_兜底none带标记() {
        stubDict("600519");
        when(quotes.ensureBars(anyString(), anyInt()))
                .thenReturn(List.of(DailyBar.builder().close(10).volume(1).build()));
        Map<String, Object> out = tool.radarCheck("600519", null, null, null, null);
        assertThat(out.get("signal")).isEqualTo("none");
        assertThat((Boolean) out.get("dataInsufficient")).isTrue();
    }

    @Test
    void 股票解析失败_none带error() {
        when(dict.resolve("???")).thenReturn(Optional.empty());
        Map<String, Object> out = tool.radarCheck("???", null, null, null, null);
        assertThat(out.get("signal")).isEqualTo("none");
        assertThat(String.valueOf(out.get("error"))).contains("无法解析");
    }

    // ========== maWindow / 阈值显式槽位 ==========

    @Test
    void 自定义maWindow_按窗口取数与求均值() {
        stubDict("600519");
        when(quotes.ensureBars(anyString(), anyInt())).thenReturn(bars(10.0, 100));
        Map<String, Object> out = tool.radarCheck("600519", 10, null, null, null);
        assertThat(out.get("maWindow")).isEqualTo(10);
        assertThat((Double) out.get("ma")).isEqualTo(10.0);
        Mockito.verify(quotes).ensureBars(Mockito.eq("600519"), Mockito.eq(20));
    }

    @Test
    void maWindow超界_钳制到边界() {
        stubDict("600519");
        when(quotes.ensureBars(anyString(), anyInt())).thenReturn(bars(10.0, 100, 130));
        assertThat(tool.radarCheck("600519", 3, null, null, null).get("maWindow")).isEqualTo(5);
        assertThat(tool.radarCheck("600519", 500, null, null, null).get("maWindow")).isEqualTo(120);
    }

    @Test
    void volumeRatio超界_钳制上限() {
        stubBars(10.0, 100);
        assertThat(tool.radarCheck("600519", null, -1.0, null, null).get("volumeRatioThreshold")).isEqualTo(2.0);
        assertThat(tool.radarCheck("600519", null, 999.0, null, null).get("volumeRatioThreshold")).isEqualTo(100.0);
    }

    // ========== conditions 扩展 ==========

    @Test
    void 未知条件token_报错带可用清单() {
        stubBars(10.0, 100);
        Map<String, Object> out = tool.radarCheck("600519", null, null, List.of("foo_bar"), null);
        assertThat(String.valueOf(out.get("error"))).contains("ma_break");
    }

    @Test
    void pctUp_达阈值命中_阈值可自定义() {
        stubBars(10.5, 100);
        Map<String, Object> out = tool.radarCheck("600519", null, null, List.of("pct_up"), 3.0);
        assertThat((Double) out.get("changePct")).isEqualTo(5.0);
        assertThat(out.get("matched")).isEqualTo(List.of("pct_up"));
        assertThat((Boolean) out.get("allMatched")).isTrue();
    }

    @Test
    void pctDown_未达阈值_不命中() {
        stubBars(9.8, 100);
        Map<String, Object> out = tool.radarCheck("600519", null, null, List.of("pct_down"), 3.0);
        assertThat((Double) out.get("changePct")).isEqualTo(-2.0);
        assertThat((Object) out.get("matched")).isEqualTo(List.of());
    }

    @Test
    void kdjOversold_单日深跌_J值为负命中() {
        stubDict("600519");
        // 33 根横盘 10 → 9.5 → 8.45 → 当日 8.4：RSV 贴地 K 骤降，J=3K-2D < 0
        List<DailyBar> list = new ArrayList<>();
        for (int i = 0; i < 33; i++) {
            list.add(bar(i, 10, 10.1, 9.9, 100));
        }
        list.add(bar(33, 9.5, 9.6, 9.4, 100));
        list.add(bar(34, 8.45, 8.5, 8.4, 100));
        list.add(bar(35, 8.4, 8.45, 8.35, 100));
        when(quotes.ensureBars(anyString(), anyInt())).thenReturn(list);
        Map<String, Object> out = tool.radarCheck("600519", null, null, List.of("kdj_oversold"), null);
        assertThat(out.get("matched")).isEqualTo(List.of("kdj_oversold"));
        assertThat((Boolean) out.get("allMatched")).isTrue();
    }

    @Test
    void macd条件_取数按36根_数据不足兜底() {
        stubDict("600519");
        when(quotes.ensureBars(anyString(), anyInt())).thenReturn(bars(10.0, 100));
        Map<String, Object> out = tool.radarCheck("600519", null, null, List.of("macd_golden"), null);
        Mockito.verify(quotes).ensureBars(Mockito.eq("600519"), Mockito.eq(36));
        assertThat((Boolean) out.get("dataInsufficient")).isTrue();
    }

    @Test
    void resistanceBreak_收盘穿越最近压力带命中() {
        stubDict("600519");
        // 129 根横盘 10（枢轴 P=10 为最近压力带）+ 当日 10.5 穿越带中心
        when(quotes.ensureBars(anyString(), anyInt())).thenReturn(bars(10.5, 100, 130));
        Map<String, Object> out = tool.radarCheck("600519", null, null, List.of("resistance_break"), null);
        assertThat(out.get("matched")).isEqualTo(List.of("resistance_break"));
        assertThat(((Map<?, ?>) out.get("levelBreak")).get("direction")).isEqualTo("resistance");
    }

    // ========== 批量过筛 ==========

    @Test
    void 批量_命中汇总与unresolved分流() {
        stubDict("600519");
        stubDict("SZ000001");
        when(dict.resolve("???")).thenReturn(Optional.empty());
        when(quotes.ensureBars(anyString(), anyInt())).thenReturn(bars(10.5, 100));
        Map<String, Object> out = batchTool.radarBatch(
                List.of("600519", "SZ000001", "???", ""), List.of("pct_up"), null, null, 3.0);
        assertThat(out.get("requested")).isEqualTo(3);
        assertThat(out.get("resolved")).isEqualTo(2);
        assertThat(out.get("hitCount")).isEqualTo(2);
        assertThat((List<?>) out.get("results")).hasSize(2);
        assertThat((List<?>) out.get("unresolved")).hasSize(1);
        assertThat(((Map<?, ?>) ((List<?>) out.get("unresolved")).get(0)).get("stock")).isEqualTo("???");
    }

    @Test
    void 批量_超上限报错_空列表报错() {
        List<String> tooMany = new ArrayList<>();
        for (int i = 0; i < 51; i++) {
            tooMany.add("6005" + String.format("%02d", i));
        }
        assertThat(String.valueOf(batchTool.radarBatch(tooMany, null, null, null, null).get("error"))).contains("50");
        assertThat(String.valueOf(batchTool.radarBatch(List.of(), null, null, null, null).get("error"))).contains("缺失");
    }

    @Test
    void 批量_缺省条件_按signal派生命中() {
        stubBars(10.5, 300);
        Map<String, Object> out = batchTool.radarBatch(List.of("600519"), null, null, null, null);
        assertThat(out.get("hitCount")).isEqualTo(1);
        assertThat(((Map<?, ?>) ((List<?>) out.get("results")).get(0)).get("matched"))
                .isEqualTo(List.of("ma_break", "volume_surge"));
    }
}
