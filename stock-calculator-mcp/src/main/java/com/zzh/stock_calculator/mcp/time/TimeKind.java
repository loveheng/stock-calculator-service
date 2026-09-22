package com.zzh.stock_calculator.mcp.time;

/**
 * 解析结果类别：POINT=明确时间点（start==end）；RANGE=明确区间；RECURRING=周期频次（配合 cron 表达式）；
 * NEED_CONFIRM=过于模糊，无法唯一定值，返回候选区间交人工确认。
 */
public enum TimeKind {
    POINT, RANGE, RECURRING, NEED_CONFIRM
}
