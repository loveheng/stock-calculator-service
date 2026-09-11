package com.zzh.stockcalc.contract.message;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 文章-股票关联（对应 cls_article_stock，含价格/涨幅快照）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClsStockLink {

    private Long articleId;
    private String stockId;
    private BigDecimal lastPrice;
    private BigDecimal riseRange;
}
