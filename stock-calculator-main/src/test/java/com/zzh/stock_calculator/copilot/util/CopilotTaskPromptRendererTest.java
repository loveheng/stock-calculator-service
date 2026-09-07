package com.zzh.stock_calculator.copilot.util;

import org.junit.jupiter.api.Test;

import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CopilotTaskPromptRenderer 单元测试（纯 JUnit，无 Spring 上下文）：
 * taskType 路由（缺省/未知/模版缺失/读取异常 → null 回落聊天链路）、占位符渲染、
 * contextSummary 中 sampleRows/draftContext 的容错提取（根级 → data 子对象 → 样例原文回退）。
 */
class CopilotTaskPromptRendererTest {

    private static final String TEMPLATE = "契约[SAMPLE]{SAMPLE_ROWS}[/SAMPLE] 迭代[{DRAFT_CONTEXT}] 需求[{USER_CONTENT}]";

    /** reader 桩：命中固定模版 */
    private final Function<String, String> reader = tag -> CopilotTaskPromptRenderer.TAG_CUSTOM_STAT_GEN.equals(tag)
            ? TEMPLATE : null;

    // ==================== 路由：taskType 决定走任务模版还是回落 ====================

    @Test
    void customStat_routesToTaskTemplate() {
        String prompt = CopilotTaskPromptRenderer.buildCustomStatSystemPrompt(
                "custom_stat", null, "各股做T收益排行", reader);
        assertTrue(prompt.contains("各股做T收益排行"));
    }

    @Test
    void blankOrNullTaskType_fallsBackToNull() {
        assertNull(CopilotTaskPromptRenderer.buildCustomStatSystemPrompt(null, null, "q", reader));
        assertNull(CopilotTaskPromptRenderer.buildCustomStatSystemPrompt("  ", null, "q", reader));
    }

    @Test
    void unknownTaskType_fallsBackToNull() {
        // 未知值宽松回落（不报错），且有模版也不路由
        assertNull(CopilotTaskPromptRenderer.buildCustomStatSystemPrompt("other_task", null, "q", reader));
    }

    @Test
    void taskTypeTrimmed() {
        String prompt = CopilotTaskPromptRenderer.buildCustomStatSystemPrompt(
                "  custom_stat  ", null, "q", reader);
        assertTrue(prompt != null && prompt.contains("q"));
    }

    @Test
    void templateMiss_fallsBackToNull() {
        assertNull(CopilotTaskPromptRenderer.buildCustomStatSystemPrompt(
                "custom_stat", null, "q", tag -> null));
    }

    @Test
    void templateReaderThrows_fallsBackToNull() {
        assertNull(CopilotTaskPromptRenderer.buildCustomStatSystemPrompt(
                "custom_stat", null, "q", tag -> { throw new IllegalStateException("redis down"); }));
    }

    @Test
    void nullReader_fallsBackToNull() {
        assertNull(CopilotTaskPromptRenderer.buildCustomStatSystemPrompt("custom_stat", null, "q", null));
    }

    // ==================== 占位符渲染 ====================

    @Test
    void firstGeneration_draftPlaceholderGetsFirstRoundText() {
        String prompt = CopilotTaskPromptRenderer.buildCustomStatSystemPrompt(
                "custom_stat", null, "q", reader);
        assertTrue(prompt.contains("迭代[首轮生成：无既有代码，直接按用户需求生成。]"));
    }

    @Test
    void iteration_draftContextFilledWithReplaceInstruction() {
        String ctx = "{\"draftContext\":{\"prompt\":\"种子\",\"code\":\"(ctx) => 1\",\"feedback\":\"按月分组\"}}";
        String prompt = CopilotTaskPromptRenderer.buildCustomStatSystemPrompt(
                "custom_stat", ctx, "按月分组", reader);
        assertTrue(prompt.contains("迭代[迭代轮：必须基于下方当前草稿修改"));
        assertTrue(prompt.contains("\"feedback\":\"按月分组\""));
        assertFalse(prompt.contains("首轮生成"));
    }

    @Test
    void missingSamples_getsPlaceholderNote() {
        String prompt = CopilotTaskPromptRenderer.buildCustomStatSystemPrompt(
                "custom_stat", null, "q", reader);
        assertTrue(prompt.contains("[SAMPLE]（未提供样例行）[/SAMPLE]"));
    }

    // ==================== sampleRows 提取：根级 → data 子对象 → 原文回退 ====================

    @Test
    void sampleRowsAtRoot_extractedAsJson() {
        String ctx = "{\"sampleRows\":{\"rounds\":[{\"netProfit\":1.5}]}}";
        String prompt = CopilotTaskPromptRenderer.buildCustomStatSystemPrompt(
                "custom_stat", ctx, "q", reader);
        assertTrue(prompt.contains("[SAMPLE]{\"rounds\":[{\"netProfit\":1.5}]}[/SAMPLE]"));
    }

    @Test
    void sampleRowsUnderData_extracted() {
        String ctx = "{\"capturedAt\":1,\"data\":{\"sampleRows\":{\"rounds\":[]}}}";
        String prompt = CopilotTaskPromptRenderer.buildCustomStatSystemPrompt(
                "custom_stat", ctx, "q", reader);
        assertTrue(prompt.contains("[SAMPLE]{\"rounds\":[]}[/SAMPLE]"));
    }

    @Test
    void sampleRowsKeyMissing_fallsBackToRawContextSummary() {
        // 契约兜底：该场景 contextSummary 内容即样例行，前端未按键名包装时整段直填
        String ctx = "{\"rounds\":[{\"netProfit\":9}]}";
        String prompt = CopilotTaskPromptRenderer.buildCustomStatSystemPrompt(
                "custom_stat", ctx, "q", reader);
        assertTrue(prompt.contains("[SAMPLE]{\"rounds\":[{\"netProfit\":9}]}[/SAMPLE]"));
    }

    @Test
    void invalidJsonContext_sampleRowsEmptyAndDraftFirstRound() {
        String prompt = CopilotTaskPromptRenderer.buildCustomStatSystemPrompt(
                "custom_stat", "not-json", "q", reader);
        // 非法 JSON：样例键与草稿键均视为缺失；但字符串非空非白 → 样例回退原文（契约兜底）
        assertTrue(prompt.contains("[SAMPLE]not-json[/SAMPLE]"));
        assertTrue(prompt.contains("首轮生成"));
    }

    @Test
    void draftContextNeverFallsBackToRaw() {
        // draftContext 仅按键提取，缺失即首轮语义（不回退原文，防把样例误当草稿）
        String ctx = "{\"sampleRows\":{\"rounds\":[]}}";
        String prompt = CopilotTaskPromptRenderer.buildCustomStatSystemPrompt(
                "custom_stat", ctx, "q", reader);
        assertTrue(prompt.contains("首轮生成"));
    }

    // ==================== 渲染健壮性 ====================

    @Test
    void questionContainingPlaceholderStyleText_isSafeLiteral() {
        String prompt = CopilotTaskPromptRenderer.buildCustomStatSystemPrompt(
                "custom_stat", null, "需求含 {SAMPLE_ROWS} 字样", reader);
        // 精确字面量替换 + USER_CONTENT 最后替换：用户文本中的占位符样式内容保持字面量，不被二次展开
        assertTrue(prompt.contains("需求[需求含 {SAMPLE_ROWS} 字样]"));
        assertFalse(prompt.contains("需求[需求含 （未提供样例行） 字样]"));
    }

    @Test
    void nullQuestion_rendersEmpty() {
        String prompt = CopilotTaskPromptRenderer.buildCustomStatSystemPrompt(
                "custom_stat", null, null, reader);
        assertTrue(prompt.contains("需求[]"));
    }
}
