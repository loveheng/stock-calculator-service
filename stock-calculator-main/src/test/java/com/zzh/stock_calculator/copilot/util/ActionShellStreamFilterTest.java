package com.zzh.stock_calculator.copilot.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 动作外壳流式截断器单测（规范⑤）：delta 通道永不漏外壳片段（含跨 chunk 拆分的半截标签），
 * 口径与 CopilotStatActionExtractor.parse 的 cleanedText 逐条对齐——各用例均以
 * 「filter 串联结果 == parse cleanedText」为终判（流式视图 = 权威全文增量前缀）。
 */
class ActionShellStreamFilterTest {

    private static final String OPEN = CopilotStatActionExtractor.OPEN_TAG;
    private static final String CLOSE = CopilotStatActionExtractor.CLOSE_TAG;

    private static final String SHELL = OPEN + "{\"actions\":[{\"type\":\"canvas_add_block\","
            + "\"payload\":{\"type\":\"brief\"}}]}" + CLOSE;

    private String stream(ActionShellStreamFilter filter, String... deltas) {
        StringBuilder emitted = new StringBuilder();
        for (String delta : deltas) {
            emitted.append(filter.filter(delta));
        }
        emitted.append(filter.flush());
        return emitted.toString();
    }

    private void assertParityWithParse(String raw) {
        ActionShellStreamFilter filter = new ActionShellStreamFilter();
        String streamed = stream(filter, raw.split("(?<=.)", -1)); // 逐字符喂流
        CopilotStatActionExtractor.Parsed parsed = CopilotStatActionExtractor.parse(raw);
        String cleaned = parsed == null ? raw : parsed.cleanedText();
        assertThat(streamed).isEqualTo(cleaned);
    }

    @Test
    void noShellPassesThrough() {
        assertThat(stream(new ActionShellStreamFilter(), "你好", "，今天行情不错")).isEqualTo("你好，今天行情不错");
    }

    @Test
    void shellNeverLeaksEvenSplitAcrossChunks() {
        ActionShellStreamFilter filter = new ActionShellStreamFilter();
        String emitted = stream(filter, "茅台已建档。", SHELL.substring(0, 8), SHELL.substring(8));
        assertThat(emitted).isEqualTo("茅台已建档。");
        assertThat(emitted).doesNotContain("<copilot");
    }

    @Test
    void proseBeforeShellForwarded_evenWhenSameChunk() {
        // 旧整段抑制的缺陷回归锚点：开标签所在 chunk 的正文前缀必须保住
        ActionShellStreamFilter filter = new ActionShellStreamFilter();
        String emitted = stream(filter, "已建档" + SHELL);
        assertThat(emitted).isEqualTo("已建档");
    }

    @Test
    void partialTagTailNeverFlashes_midStreamHeld() {
        ActionShellStreamFilter filter = new ActionShellStreamFilter();
        String first = filter.filter("结论如上<copilot-act");
        assertThat(first).isEqualTo("结论如上");
        String second = filter.filter("ions>{\"actions\":[]}</copilot-actions>");
        assertThat(second).doesNotContain("<copilot");
    }

    @Test
    void partialTagThatNeverCompletes_flushedAsProse() {
        // 流尾是普通文本里的 < 符号片段：按原文补发（parse 无完整标签=原文口径）
        ActionShellStreamFilter filter = new ActionShellStreamFilter();
        String emitted = stream(filter, "涨幅 <5% 的标的");
        assertThat(emitted).isEqualTo("涨幅 <5% 的标的");
    }

    @Test
    void unclosedShellAtEnd_dropped() {
        // 未闭合残块：parse 剔除到结尾，流式同口径丢弃
        assertParityWithParse("结论如上。" + OPEN + "{\"actions\":[");
    }

    @Test
    void proseAfterCloseTagResumes() {
        // 壳后正文：parse 保留，流式闭合后恢复下发（同口径）
        assertParityWithParse("结论。" + SHELL + "补充说明。");
    }

    @Test
    void multipleShells_lastCompleteBlockWins_bothStripped() {
        assertParityWithParse("开头。" + SHELL + "中段。" + SHELL + "结尾。");
    }

    @Test
    void charByCharStream_matchesParseCleanedText_forMixedText() {
        String raw = "第一段。" + SHELL + "中段有 <5% 字样。" + SHELL;
        assertParityWithParse(raw);
    }
}
