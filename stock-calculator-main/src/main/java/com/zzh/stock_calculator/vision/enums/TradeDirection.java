package com.zzh.stock_calculator.vision.enums;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum TradeDirection {

    BUY("BUY", "买入"),
    SELL("SELL", "卖出");

    @JsonValue
    private final String code;
    private final String description;

    @JsonCreator
    public static TradeDirection fromCode(String code) {
        if (code == null || code.isBlank()) {
            return BUY;
        }
        String clean = code.trim().toUpperCase();
        if (clean.contains("BUY") || clean.contains("买")) {
            return BUY;
        }
        if (clean.contains("SELL") || clean.contains("卖")) {
            return SELL;
        }
        return BUY;
    }
}
