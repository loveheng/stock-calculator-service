package com.zzh.stock_calculator.mcp.kb;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 待入库书块草稿（切块产物，向量另行批量计算）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class KbChunkDraft {

    private String chapterPath;

    private int chunkIndex;

    private String content;

    private String contentHash;
}
