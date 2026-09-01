package com.zzh.stock_calculator.vision.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PromptFormatter 单元测试：保守清洗规则（零宽字符/BOM、行尾空白、压缩连续空行）
 * 与 Prompt 组装模板。
 */
class PromptFormatterTest {

    private final PromptFormatter formatter = new PromptFormatter();

    @Test
    void cleanRemovesZeroWidthBomAndTrailingSpaces() {
        String raw = "\uFEFF600745 中际旭创\u200B  \n买入 100股 \n";

        String cleaned = formatter.clean(raw);

        assertEquals("600745 中际旭创\n买入 100股", cleaned);
        assertFalse(cleaned.contains("\u200B"));
        assertFalse(cleaned.contains("\uFEFF"));
    }

    @Test
    void cleanCollapsesConsecutiveBlankLines() {
        String raw = "A\n\n\n\nB\n\n\nC";

        assertEquals("A\n\nB\n\nC", formatter.clean(raw));
    }

    @Test
    void cleanKeepsNumbersAndLineContentIntact() {
        String raw = "成交价格 16.690\n成交数量 100";

        assertEquals("成交价格 16.690\n成交数量 100", formatter.clean(raw));
    }

    @Test
    void cleanReturnsEmptyForBlankInput() {
        assertEquals("", formatter.clean(null));
        assertEquals("", formatter.clean("   \n \n "));
    }

    @Test
    void buildUserMessageContainsInstructionAndText() {
        String message = formatter.buildUserMessage("提取交易记录", "600745 中际旭创");

        assertTrue(message.contains("【任务指令】"));
        assertTrue(message.contains("提取交易记录"));
        assertTrue(message.contains("【待处理文本】"));
        assertTrue(message.contains("600745 中际旭创"));
    }

    @Test
    void buildSystemPromptIsStableAndNonBlank() {
        String prompt = formatter.buildSystemPrompt();

        assertTrue(prompt.contains("OCR"));
        assertTrue(prompt.contains("严禁编造"));
        assertFalse(prompt.isBlank());
    }

    @Test
    void buildTradeSystemPromptContainsSpecWithoutReviewByDefault() {
        String prompt = formatter.buildTradeSystemPrompt(false);

        assertTrue(prompt.contains("JSON 二维数组"));
        assertTrue(prompt.contains("BUY"));
        assertFalse(prompt.contains("审查模式"));
    }

    @Test
    void buildTradeSystemPromptStrictReviewAppendsReviewSection() {
        String prompt = formatter.buildTradeSystemPrompt(true);

        assertTrue(prompt.contains("审查模式"));
        assertTrue(prompt.contains("0/6/8"));
    }

    @Test
    void buildTradeUserMessageContainsCleanedText() {
        String message = formatter.buildTradeUserMessage("600745 中际旭创");

        assertTrue(message.contains("【待处理文本】"));
        assertTrue(message.contains("600745 中际旭创"));
    }
}
