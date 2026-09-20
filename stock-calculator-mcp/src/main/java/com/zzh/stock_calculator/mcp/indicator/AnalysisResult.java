package com.zzh.stock_calculator.mcp.indicator;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 指标全家桶结果：只含最新值 + 文字信号，不吐完整序列（粗粒度原则，design.md §七）。
 * 缺数据的指标（如上市不足 60 日的 ma60）不出现在对应 map 里。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AnalysisResult {

    private String lastDate;

    private double lastClose;

    /** 相对前收盘涨跌幅（百分比） */
    private double changePct;

    private Map<String, Double> ma;

    private Map<String, Double> macd;

    private Double rsi;

    private Map<String, Double> boll;

    private Map<String, Double> kdj;

    private List<String> signals;
}
