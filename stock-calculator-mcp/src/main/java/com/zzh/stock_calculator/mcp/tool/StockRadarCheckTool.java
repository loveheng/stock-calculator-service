package com.zzh.stock_calculator.mcp.tool;

import com.zzh.stock_calculator.mcp.indicator.RadarCheckService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * MCP 工具：多条件雷达断言（orchestration 3① 条件判定前置）。
 * 断言逻辑全部收敛在 RadarCheckService（与批量 stock_radar_batch 共用），
 * 本工具只做 LLM 入参转发——缺省行为（不传 conditions）保持旧行为：
 * signal 枚举 both/break_only/volume_only/none 供 switch 节点直接分流。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StockRadarCheckTool {

    private final RadarCheckService radarCheckService;

    @Tool(name = "stock_radar_check", description = "多条件雷达断言：检查个股是否命中指定技术面条件。"
            + "conditions 可选（JSON 数组，多选）：ma_break 突破N日均线 / volume_surge 量能放大N倍 / "
            + "macd_golden macd_dead MACD金叉死叉（近3根）/ kdj_golden kdj_dead KDJ金叉死叉 / "
            + "kdj_overbought kdj_oversold J值超买超卖 / pct_up pct_down 当日涨跌幅达阈值（pctThreshold，默认3%）/ "
            + "resistance_break support_break 收盘突破/跌破最近支撑压力位带。"
            + "不传 conditions 时只检查均线突破与量能放大，返回枚举信号 both/break_only/volume_only/none；"
            + "传 conditions 时额外返回逐条件布尔 results、命中列表 matched、是否全中 allMatched。"
            + "数据不足返回 signal=none 且 dataInsufficient=true；conditions 含未支持值返回 error 带可用清单。")
    public Map<String, Object> radarCheck(
            @ToolParam(description = "股票代码或名称，如 600519 / sh600519 / 宁德时代") String stock,
            @ToolParam(required = false, description = "均线窗口（日），默认 20，范围 5-120") Integer maWindow,
            @ToolParam(required = false, description = "量能放大倍数阈值，默认 2.0（当日量 / 前5日均量）") Double volumeRatio,
            @ToolParam(required = false, description = "条件列表，如 [\"macd_golden\",\"pct_up\"]；缺省只查均线突破+量能") List<String> conditions,
            @ToolParam(required = false, description = "涨跌幅阈值（%），默认 3，范围 0-20，仅 pct_up/pct_down 使用") Double pctThreshold) {
        return radarCheckService.check(stock, maWindow, volumeRatio, conditions, pctThreshold);
    }
}
