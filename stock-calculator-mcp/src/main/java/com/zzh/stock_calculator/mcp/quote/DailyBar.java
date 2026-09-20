package com.zzh.stock_calculator.mcp.quote;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * 单根日线（价格用 double：直接进 ta4j 计算管线，输出层再统一舍入 2 位）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DailyBar {

    private LocalDate date;

    private double open;

    private double close;

    private double high;

    private double low;

    /** 成交量（手，东财原始口径） */
    private double volume;

    /** 成交额（元） */
    private double amount;

    /** 振幅（%） */
    private double amplitude;

    /** 涨跌幅（%） */
    private double pctChg;

    /** 涨跌额（元） */
    private double chg;

    /** 换手率（%） */
    private double turnover;
}
