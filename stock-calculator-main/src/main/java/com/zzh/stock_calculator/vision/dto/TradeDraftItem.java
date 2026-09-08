package com.zzh.stock_calculator.vision.dto;
import com.zzh.stock_calculator.vision.enums.TradeDirection;
import com.zzh.stock_calculator.vision.enums.TradeStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TradeDraftItem {


    private String stockCode;     // 6位股票代码，如 600745

    private String stockName;     // 标的名称，如 *ST闻泰

    private TradeDirection direction;     // BUY / SELL

    private BigDecimal price;     // 成交价格

    private Integer volume;       // 成交数量

    private String tradeTime;     // 成交时间，格式 YYYY-MM-DD HH:mm:ss

    /** 股票代码候选：截图无代码且 Smartbox 多候选/零匹配时透传给前端人工选择；唯一匹配已回填后为空列表 */
    private List<StockCandidate> candidates;

    @Builder.Default
    private TradeStatus status = TradeStatus.FILLED;        // 默认 FILLED

}
