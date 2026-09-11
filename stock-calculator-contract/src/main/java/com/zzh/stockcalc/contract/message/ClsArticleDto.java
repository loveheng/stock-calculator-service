package com.zzh.stockcalc.contract.message;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 电报文章主表字段（对应 cls_article，含 JSONB 数组）。
 * 字段名与实体保持一致，主服务按名映射回实体。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClsArticleDto {

    private Long id;
    private Integer type;
    private String title;
    private String brief;
    private String content;
    /** 原始时间戳（秒），主表排序依据 */
    private Long ctime;
    private String author;
    private String level;
    private List<String> images;
    private List<String> audioUrl;
}
