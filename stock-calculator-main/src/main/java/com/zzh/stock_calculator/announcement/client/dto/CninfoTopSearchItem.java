package com.zzh.stock_calculator.announcement.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * CNINFO /new/information/topSearch/query 行项（S3 实证）：
 * keyWord 精确匹配 code → 取 orgId（如 000001 → gssz0000001）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class CninfoTopSearchItem {
    private String code;
    private String orgId;
    private String zwjc;
    private String category;
    private String type;
    /** 字符串 "0"/"1"（S3 实证） */
    private String delisted;
}
