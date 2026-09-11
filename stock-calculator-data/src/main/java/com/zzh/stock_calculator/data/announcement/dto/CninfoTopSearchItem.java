package com.zzh.stock_calculator.data.announcement.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * CNINFO /new/information/topSearch/query 行项（S3 实证）：
 * keyWord 精确匹配 code → 取 orgId（如 000001 → gssz0000001）。
 * <p>2026-09-11 阶段 4 任务 2 自主服务 announcement/client/dto 平移（collector 迁出 D1）。</p>
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
