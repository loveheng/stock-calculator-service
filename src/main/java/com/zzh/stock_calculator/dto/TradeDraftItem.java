package com.zzh.stock_calculator.dto;

import com.zzh.stock_calculator.enums.TradeDirection;
import com.zzh.stock_calculator.enums.TradeStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.aot.hint.annotation.RegisterReflectionForBinding;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@RegisterReflectionForBinding({TradeDraftItem.class, TradeDirection.class, TradeStatus.class})
public class TradeDraftItem {


    private String stockCode;     // 6位股票代码，如 600745

    private String stockName;     // 标的名称，如 *ST闻泰

    private TradeDirection direction;     // BUY / SELL

    private BigDecimal price;     // 成交价格

    private Integer volume;       // 成交数量

    private String tradeTime;     // 成交时间，格式 YYYY-MM-DD HH:mm:ss

    @Builder.Default
    private TradeStatus status = TradeStatus.FILLED;        // 默认 FILLED

}
