package com.zzh.stock_calculator.mcp.indicator;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 支撑/压力位带（日线级数据本质是区间，输出位带而非伪精确点位）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PriceLevel {

    private double priceLow;

    private double priceHigh;

    /** pivot=枢轴点 | swing=摆动高低点聚类 | volume=成交密集区 */
    private String type;

    /** 摆动点触及次数（其余类型为 1） */
    private int touches;

    /** 成交量占比（仅 volume 类型，百分比） */
    private double volumeShare;

    /** 位带中心相对现价距离（%） */
    private double distPct;

    private String note;
}
