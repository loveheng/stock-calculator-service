package com.zzh.stock_calculator.vision.dto;

/**
 * 股票代码候选（Smartbox 联想搜索单条结果）：
 * market 限定 sh/sz（A股），code 为 6 位数字代码，type 如 GP-A / ETF。
 * 随 TradeDraftItem.candidates 透传给前端：stockCode 未唯一匹配时供人工选择。
 */
public record StockCandidate(String market, String code, String name, String type) {
}
