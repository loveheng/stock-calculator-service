package com.zzh.stock_calculator.data.announcement.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * PDF 抽取产物（设计文档 §4.2）：逐页文本 + 页起始偏移，
 * cleanedText 为 offset 体系唯一基准串。
 */
@Data
@Builder
public class ExtractedDocument {

    private int pageCount;

    /** 原始逐页文本（已 normalize） */
    private List<String> pages;

    /** 全文（页间以 \n 连接；normalize 保证 char == code point，D12） */
    private String cleanedText;

    /** 每页在 cleanedText 中的起始偏移（单调递增，长度 = 页数） */
    private int[] pageStartOffsets;

    public static ExtractedDocument of(int pageCount, List<String> pages) {
        StringBuilder sb = new StringBuilder();
        int[] starts = new int[pages.size()];
        for (int i = 0; i < pages.size(); i++) {
            if (i > 0) {
                sb.append('\n');
            }
            starts[i] = sb.length();
            sb.append(pages.get(i));
        }
        return ExtractedDocument.builder()
                .pageCount(pageCount)
                .pages(pages)
                .cleanedText(sb.toString())
                .pageStartOffsets(starts)
                .build();
    }
}
