package com.zzh.stock_calculator.vision.service;

import com.zzh.stock_calculator.vision.dto.StockCandidate;

import java.util.List;

/**
 * 股票代码补全策略接口：交易截图中只有股票名称没有代码时，按名称搜索候选。
 *
 * <p>实现方必须 fail-open：查询失败 / 未命中一律返回空列表，绝不阻断交易草稿主流程；
 * 代码缺失的最终兜底是前端人工选择（透传 candidates）或人工录入。</p>
 */
public interface StockCodeResolver {

    /**
     * 按名称关键词搜索 A 股候选（含 ETF）。
     *
     * @param nameKeyword 股票名称（OCR 原文，实现方自行清洗 * 前缀、市场后缀等干扰字符）
     * @return 候选列表（已过滤非 A 股市场与非 6 位数字代码）；调用方约定：
     *         唯一候选静默回填 stockCode，多候选/零匹配透传 candidates 给前端。永不返回 null。
     */
    List<StockCandidate> search(String nameKeyword);
}
