package com.zzh.stock_calculator.mcp.kb;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 抽取出的一个章节（EPUB spine 单元或 txt 全文）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class KbSection {

    private String title;

    private String text;
}
