package com.zzh.stock_calculator.mcp.dict;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * stock:dict HASH 单条镜像值的内存形态（与 main 侧 StockDictRedisSync 的 JSON 字段对齐）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StockDictEntry {

    private String stockId;

    private String name;

    /** 曾用名（可能为空串） */
    private String oldName;

    /** Lombok 生成 isStib() 后 Jackson 属性名为 stib，显式钉死镜像 JSON 的 isStib 键 */
    @JsonProperty("isStib")
    private boolean stib;
}
