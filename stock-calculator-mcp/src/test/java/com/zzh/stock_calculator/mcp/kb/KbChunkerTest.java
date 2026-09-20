package com.zzh.stock_calculator.mcp.kb;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KbChunkerTest {

    private final KbChunker chunker = new KbChunker();

    private String longText(int lines) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines; i++) {
            sb.append("第").append(i).append("行：安全边际是价值投资的核心概念，用它保证句子长度超过换行切分的偏好区间。").append('\n');
        }
        return sb.toString();
    }

    @Test
    void shortSectionSingleChunk() {
        List<KbChunkDraft> drafts = chunker.chunk("测试书",
                List.of(new KbSection("第一章", "短文本")));
        assertEquals(1, drafts.size());
        assertEquals("测试书 / 第一章", drafts.get(0).getChapterPath());
        assertEquals(0, drafts.get(0).getChunkIndex());
        assertEquals(64, drafts.get(0).getContentHash().length());
    }

    @Test
    void longSectionOverlapsAndBounded() {
        List<KbChunkDraft> drafts = chunker.chunk("测试书",
                List.of(new KbSection("第二章", longText(60))));
        assertTrue(drafts.size() >= 2, "长节应切多块: " + drafts.size());
        // 全部块不超 600（换行偏好切点只会更短，硬切恰为 600）
        for (KbChunkDraft d : drafts) {
            assertTrue(d.getContent().length() <= 600, "块超长: " + d.getContent().length());
        }
        // 相邻块重叠：后块头部 40 字 == 前块尾部相应 40 字
        String tail = drafts.get(0).getContent();
        String head = drafts.get(1).getContent();
        assertTrue(tail.endsWith(head.substring(0, Math.min(40, head.length())))
                        || tail.contains(head.substring(0, Math.min(40, head.length()))),
                "相邻块应有重叠");
        // 全局 chunk_index 连续递增
        for (int i = 1; i < drafts.size(); i++) {
            assertEquals(drafts.get(i - 1).getChunkIndex() + 1, drafts.get(i).getChunkIndex());
        }
    }

    @Test
    void multiSectionsCarrySectionTitle() {
        List<KbChunkDraft> drafts = chunker.chunk("均线100分", List.of(
                new KbSection("上篇", "上篇内容"),
                new KbSection("下篇", "下篇内容")));
        assertEquals(2, drafts.size());
        assertEquals("均线100分 / 上篇", drafts.get(0).getChapterPath());
        assertEquals("均线100分 / 下篇", drafts.get(1).getChapterPath());
    }
}
