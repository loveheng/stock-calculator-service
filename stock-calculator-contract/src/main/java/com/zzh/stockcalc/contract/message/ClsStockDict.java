package com.zzh.stockcalc.contract.message;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 股票字典（对应 stock，主键 stockId；oldName 首次入库与 name 相同，由生产方填好）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClsStockDict {

    private String stockId;
    private String name;
    private String oldName;
    private Boolean isStib;
}
