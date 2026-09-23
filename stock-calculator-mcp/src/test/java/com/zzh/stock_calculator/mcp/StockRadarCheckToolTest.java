package com.zzh.stock_calculator.mcp;

import com.zzh.stock_calculator.mcp.dict.StockDictEntry;
import com.zzh.stock_calculator.mcp.dict.StockDictMemoryService;
import com.zzh.stock_calculator.mcp.quote.DailyBar;
import com.zzh.stock_calculator.mcp.quote.QuoteSyncService;
import com.zzh.stock_calculator.mcp.tool.StockRadarCheckTool;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 雷达断言工具单测（纯内存，依赖全 mock）：四信号分支 + 数据不足兜底 + 默认阈值。
 * 关键不变量：bar 构造保证 MA20 与量能条件的组合覆盖（前 20 根低位收盘、当日价量可控）。
 */
class StockRadarCheckToolTest {

    private final StockDictMemoryService dict = Mockito.mock(StockDictMemoryService.class);
    private final QuoteSyncService quotes = Mockito.mock(QuoteSyncService.class);
    private final StockRadarCheckTool tool = new StockRadarCheckTool(dict, quotes);

    private void stubDict() {
        StockDictEntry entry = new StockDictEntry();
        entry.setStockId("600519");
        entry.setName("贵州茅台");
        when(dict.resolve(anyString())).thenReturn(Optional.of(entry));
    }

    /** 前 20 根收盘 10（MA20=10），前 5 根量 100（前5均量=100），当日可调 */
    private List<DailyBar> bars(double lastClose, double lastVolume) {
        java.util.List<DailyBar> list = new java.util.ArrayList<>();
        for (int i = 0; i < 25; i++) {
            list.add(DailyBar.builder()
                    .date(LocalDate.of(2026, 8, 1).plusDays(i))
                    .close(10).volume(100).build());
        }
        list.set(24, DailyBar.builder().date(LocalDate.of(2026, 8, 26))
                .close(lastClose).volume(lastVolume).build());
        return list;
    }

    private void stubBars(double lastClose, double lastVolume) {
        stubDict();
        when(quotes.ensureBars(anyString(), anyInt())).thenReturn(bars(lastClose, lastVolume));
    }

    @Test
    void 破线且量增_both() {
        stubBars(10.5, 300);
        Map<String, Object> out = tool.radarCheck("600519", null, null);
        assertThat(out.get("signal")).isEqualTo("both");
        assertThat((Boolean) out.get("breakAboveMa")).isTrue();
        assertThat((Double) out.get("volumeMultiple")).isEqualTo(3.0);
    }

    @Test
    void 只破线_volume不足_breakOnly() {
        stubBars(10.5, 100);
        Map<String, Object> out = tool.radarCheck("600519", null, null);
        assertThat(out.get("signal")).isEqualTo("break_only");
    }

    @Test
    void 只量增_未破线_volumeOnly() {
        stubBars(9.5, 300);
        Map<String, Object> out = tool.radarCheck("600519", null, null);
        assertThat(out.get("signal")).isEqualTo("volume_only");
    }

    @Test
    void 都不满足_none() {
        stubBars(9.5, 100);
        Map<String, Object> out = tool.radarCheck("600519", null, null);
        assertThat(out.get("signal")).isEqualTo("none");
    }

    @Test
    void 日线不足_兜底none带标记() {
        stubDict();
        when(quotes.ensureBars(anyString(), anyInt()))
                .thenReturn(List.of(DailyBar.builder().close(10).volume(1).build()));
        Map<String, Object> out = tool.radarCheck("600519", null, null);
        assertThat(out.get("signal")).isEqualTo("none");
        assertThat((Boolean) out.get("dataInsufficient")).isTrue();
    }

    @Test
    void 股票解析失败_none带error() {
        when(dict.resolve(anyString())).thenReturn(Optional.empty());
        Map<String, Object> out = tool.radarCheck("???", null, null);
        assertThat(out.get("signal")).isEqualTo("none");
        assertThat(String.valueOf(out.get("error"))).contains("无法解析");
    }

    // ========== maWindow 显式槽位 ==========

    @Test
    void 自定义maWindow_按窗口取数与求均值() {
        stubDict();
        // 默认前 25 根收盘 10；window=10 时只需 11 根有效，MA=10
        when(quotes.ensureBars(anyString(), anyInt())).thenReturn(bars(10.0, 100));
        Map<String, Object> out = tool.radarCheck("600519", 10, null);
        assertThat(out.get("maWindow")).isEqualTo(10);
        assertThat((Double) out.get("ma")).isEqualTo(10.0);
        // 取数请求为 window+10 根
        Mockito.verify(quotes).ensureBars(Mockito.eq("600519"), Mockito.eq(20));
    }

    @Test
    void maWindow超界_钳制到边界() {
        stubDict();
        // 130 根足够覆盖钳制后的最大窗口 120（需 121 根），避免走 dataInsufficient 分支
        java.util.List<DailyBar> many = new java.util.ArrayList<>();
        for (int i = 0; i < 130; i++) {
            many.add(DailyBar.builder().date(LocalDate.of(2026, 5, 1).plusDays(i))
                    .close(10).volume(100).build());
        }
        when(quotes.ensureBars(anyString(), anyInt())).thenReturn(many);
        // 3 → 钳到 5；500 → 钳到 120
        assertThat(tool.radarCheck("600519", 3, null).get("maWindow")).isEqualTo(5);
        assertThat(tool.radarCheck("600519", 500, null).get("maWindow")).isEqualTo(120);
    }

    @Test
    void volumeRatio超界_钳制上限() {
        stubDict();
        when(quotes.ensureBars(anyString(), anyInt())).thenReturn(bars(10.0, 100));
        assertThat(tool.radarCheck("600519", null, -1.0).get("volumeRatioThreshold")).isEqualTo(2.0);
        assertThat(tool.radarCheck("600519", null, 999.0).get("volumeRatioThreshold")).isEqualTo(100.0);
    }
}
