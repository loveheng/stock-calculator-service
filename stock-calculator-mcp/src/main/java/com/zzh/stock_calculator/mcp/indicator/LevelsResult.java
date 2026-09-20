package com.zzh.stock_calculator.mcp.indicator;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 支撑压力计算结果：现价上方的压力位（由近及远）与下方的支撑位（由近及远）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LevelsResult {

    private String lastDate;

    private double lastClose;

    private List<PriceLevel> resistances;

    private List<PriceLevel> supports;
}
