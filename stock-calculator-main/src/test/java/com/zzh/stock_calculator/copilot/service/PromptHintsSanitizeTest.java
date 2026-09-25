package com.zzh.stock_calculator.copilot.service;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * sanitizePromptHints 单元测试（纯 JUnit，无 Spring 上下文）：
 * blank → null 省略整段、限内原样透传、超限 8192 字节硬顶与多字节字符边界截断（防腐契约见方法注释）。
 */
class PromptHintsSanitizeTest {

    // ==================== blank 省略 ====================

    @Test
    void nullOrBlank_returnsNull() {
        assertNull(AiChatOrchestrationService.sanitizePromptHints(null));
        assertNull(AiChatOrchestrationService.sanitizePromptHints(""));
        assertNull(AiChatOrchestrationService.sanitizePromptHints("   \n\t "));
    }

    // ==================== 限内原样透传 ====================

    @Test
    void withinLimit_passthroughVerbatim() {
        String raw = "画布能力说明：支持 canvas_add_block / execute_local_calc 动作。";
        assertEquals(raw, AiChatOrchestrationService.sanitizePromptHints(raw));
    }

    @Test
    void exactlyLimitBytes_unchanged() {
        String raw = "a".repeat(8192);
        assertEquals(raw, AiChatOrchestrationService.sanitizePromptHints(raw));
    }

    // ==================== 超限截断 ====================

    @Test
    void asciiOverLimit_truncatedToCap() {
        String raw = "a".repeat(9000);
        String out = AiChatOrchestrationService.sanitizePromptHints(raw);
        assertEquals(8192, out.length());
        assertEquals(raw.substring(0, 8192), out);
    }

    @Test
    void cjkOverLimit_truncatesOnCharBoundary() {
        // 「中」= 3 字节：8192 = 3*2730 + 2，截断点落在最后一个字符内部
        String raw = "中".repeat(3000); // 9000 bytes
        String out = AiChatOrchestrationService.sanitizePromptHints(raw);
        assertTrue(out.getBytes(StandardCharsets.UTF_8).length <= 8192);
        assertFalse(out.contains("\uFFFD"));
        assertEquals(raw.substring(0, 2730), out); // 回退后 8190 = 3*2730 个完整字符
    }

    @Test
    void emojiOverLimit_truncatesOnCharBoundary() {
        // 😀 = 4 字节，前缀 1 个 ASCII 使截断点落在字符中间（lead + 2 续字节被保留的残局）
        String raw = "a" + "\uD83D\uDE00".repeat(2049); // 1 + 8196 = 8197 bytes
        String out = AiChatOrchestrationService.sanitizePromptHints(raw);
        assertTrue(out.getBytes(StandardCharsets.UTF_8).length <= 8192);
        assertFalse(out.contains("\uFFFD"));
        assertEquals("a" + "\uD83D\uDE00".repeat(2047), out); // 回退后 1 + 8188 = 8189 bytes
    }
}
