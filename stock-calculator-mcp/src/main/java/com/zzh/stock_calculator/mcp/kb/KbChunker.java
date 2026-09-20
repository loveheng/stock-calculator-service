package com.zzh.stock_calculator.mcp.kb;

import org.springframework.stereotype.Component;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/**
 * 切块：600 字窗口 / 80 字重叠，优先在窗口尾部附近最近的换行处切（不腰斩句子），
 * 无换行硬切。跨块重叠保证上下文连续（design.md §8.2）。
 */
@Component
public class KbChunker {

    private static final int SIZE = 600;
    private static final int OVERLAP = 80;

    public List<KbChunkDraft> chunk(String bookTitle, List<KbSection> sections) {
        List<KbChunkDraft> drafts = new ArrayList<>();
        int index = 0;
        for (KbSection section : sections) {
            String text = section.getText();
            if (text.isBlank()) {
                continue;
            }
            String chapterPath = bookTitle + " / " + section.getTitle();
            if (text.length() <= SIZE) {
                drafts.add(new KbChunkDraft(chapterPath, index++, text, sha256(text)));
                continue;
            }
            int start = 0;
            while (start < text.length()) {
                int cut = cutPoint(text, start);
                String piece = text.substring(start, cut).strip();
                if (!piece.isEmpty()) {
                    drafts.add(new KbChunkDraft(chapterPath, index++, piece, sha256(piece)));
                }
                if (cut >= text.length()) {
                    break;
                }
                start = Math.max(cut - OVERLAP, start + 1);
            }
        }
        return drafts;
    }

    /** 切点：窗口末端附近（70%-100%）最近换行，找不到则窗口末端 */
    private int cutPoint(String text, int start) {
        int hardEnd = Math.min(start + SIZE, text.length());
        if (hardEnd == text.length()) {
            return hardEnd;
        }
        int searchFrom = Math.max(start + (int) (SIZE * 0.7), start + 1);
        int soft = text.lastIndexOf('\n', hardEnd);
        if (soft >= searchFrom) {
            return soft;
        }
        return hardEnd;
    }

    public static String sha256(String content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16))
                        .append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("sha256 不可用", e);
        }
    }
}
