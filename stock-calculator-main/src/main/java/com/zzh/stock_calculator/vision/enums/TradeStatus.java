package com.zzh.stock_calculator.vision.enums;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum TradeStatus {

    FILLED("FILLED", "全部成交"),
    PARTIALLY_FILLED("PARTIALLY_FILLED", "部分成交"),
    CANCELLED("CANCELLED", "已撤单");

    @JsonValue
    private final String code;
    private final String description;

    @JsonCreator
    public static TradeStatus fromCode(String code) {
        if (code == null || code.isBlank()) {
            return FILLED;
        }
        for (TradeStatus status : values()) {
            if (status.code.equalsIgnoreCase(code.trim())) {
                return status;
            }
        }
        return FILLED; // 容错默认值
    }
}
