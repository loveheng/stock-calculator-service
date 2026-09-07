package com.zzh.stock_calculator.copilot.util;

import com.zzh.stock_calculator.copilot.dto.CopilotDtos.CopilotActionItem;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CopilotStatActionExtractor 单元测试（纯 JUnit，无 Spring 上下文）：
 * 动作块提取（{"actions":[...]} 与裸数组两种信封）、块剔除（完整块 + 未闭合尾部残块）、
 * 容错红线（无块/非法 JSON/结构不符 → fail-open 返回 null，文本原样）与条目级守卫（缺 type 跳过等）。
 */
class CopilotStatActionExtractorTest {

    private static final String OPEN = CopilotStatActionExtractor.OPEN_TAG;
    private static final String CLOSE = CopilotStatActionExtractor.CLOSE_TAG;

    private static String block(String inner) {
        return OPEN + inner + CLOSE;
    }

    // ==================== 正常提取：信封与剔除 ====================

    @Test
    void validEnvelopeBlock_parsesActionsAndStripsBlock() {
        String text = "分析完成。\n" + block("{\"actions\":[{\"type\":\"run_custom_stat\","
                + "\"payload\":{\"code\":\"(ctx)=>1\",\"name\":\"做T收益\"}}]}");

        CopilotStatActionExtractor.Parsed parsed = CopilotStatActionExtractor.parse(text);

        assertNotNull(parsed);
        assertEquals("分析完成。", parsed.cleanedText());
        assertEquals(1, parsed.actions().size());
        CopilotActionItem item = parsed.actions().get(0);
        assertEquals("run_custom_stat", item.getType());
        assertTrue(item.getPayload() instanceof Map<?, ?> map && "做T收益".equals(map.get("name")));
    }

    @Test
    void bareArrayEnvelope_alsoAccepted() {
        String text = block("[{\"type\":\"a\"},{\"type\":\"b\",\"payload\":[1,2]}]");

        CopilotStatActionExtractor.Parsed parsed = CopilotStatActionExtractor.parse(text);

        assertNotNull(parsed);
        assertEquals(2, parsed.actions().size());
        assertEquals("a", parsed.actions().get(0).getType());
        // 缺 payload → 空对象兜底；payload 为数组 → 原样保留
        assertEquals(Map.of(), parsed.actions().get(0).getPayload());
        assertTrue(parsed.actions().get(1).getPayload() instanceof List<?> list && list.size() == 2);
        assertEquals("", parsed.cleanedText());
    }

    @Test
    void blockInMiddle_textAfterBlockPreserved() {
        String text = "前文" + block("{\"actions\":[{\"type\":\"t\"}]}") + "后文";

        CopilotStatActionExtractor.Parsed parsed = CopilotStatActionExtractor.parse(text);

        assertEquals("前文后文", parsed.cleanedText());
        assertEquals(1, parsed.actions().size());
    }

    @Test
    void whitespaceInsideBlock_tolerated() {
        CopilotStatActionExtractor.Parsed parsed =
                CopilotStatActionExtractor.parse(OPEN + "  \n {\"actions\":[{\"type\":\"t\"}]} \n " + CLOSE);

        assertNotNull(parsed);
        assertEquals("t", parsed.actions().get(0).getType());
    }

    // ==================== 容错红线：fail-open，绝不抛错 ====================

    @Test
    void noBlock_returnsNullAndTextUntouched() {
        assertNull(CopilotStatActionExtractor.parse("普通聊天回复，没有动作块"));
        assertNull(CopilotStatActionExtractor.parse(""));
        assertNull(CopilotStatActionExtractor.parse(null));
    }

    @Test
    void invalidJsonInCompleteBlock_blockStillStrippedActionsNull() {
        String text = "回答正文" + block("{这不是JSON");

        CopilotStatActionExtractor.Parsed parsed = CopilotStatActionExtractor.parse(text);

        // 块照剔（机器 JSON 不进气泡），actions 置 null（前端守卫兜底）
        assertEquals("回答正文", parsed.cleanedText());
        assertNull(parsed.actions());
    }

    @Test
    void nonActionStructureInBlock_failOpen() {
        // 对象无 actions 键 / actions 非数组 / 根为标量 → 均视为未产出动作
        String text = "正文" + block("{\"actions\":\"不是数组\"}");

        CopilotStatActionExtractor.Parsed parsed = CopilotStatActionExtractor.parse(text);

        assertEquals("正文", parsed.cleanedText());
        assertNull(parsed.actions());
    }

    @Test
    void allEntriesInvalid_actionsNull() {
        String text = "正文" + block("[{\"payload\":{}},{\"type\":\"  \"}]");

        CopilotStatActionExtractor.Parsed parsed = CopilotStatActionExtractor.parse(text);

        assertNull(parsed.actions());
    }

    // ==================== 未闭合尾部残块（LLM 截断） ====================

    @Test
    void unclosedTrailingBlock_strippedFromOpenTagOn() {
        // LLM 输出被截断：开标签之后恒为机器文本，全部剔除，不进气泡
        String text = "回答正文" + OPEN + "{\"actions\":[{\"type\":\"run_cus";

        CopilotStatActionExtractor.Parsed parsed = CopilotStatActionExtractor.parse(text);

        assertNotNull(parsed);
        assertEquals("回答正文", parsed.cleanedText());
        assertNull(parsed.actions());
    }

    @Test
    void textStartsWithUnclosedBlock_cleanedToEmpty() {
        CopilotStatActionExtractor.Parsed parsed =
                CopilotStatActionExtractor.parse(OPEN + "{\"actions\":[{\"type\":\"t\"}");

        assertNotNull(parsed);
        assertEquals("", parsed.cleanedText());
        assertNull(parsed.actions());
    }

    @Test
    void completeBlockThenTruncatedTrailing_lastCompleteWins() {
        String text = "回答" + block("{\"actions\":[{\"type\":\"first\"}]}")
                + "补充" + OPEN + "{\"actions\":[{\"type\":\"trunc";

        CopilotStatActionExtractor.Parsed parsed = CopilotStatActionExtractor.parse(text);

        assertEquals("回答补充", parsed.cleanedText());
        assertEquals(List.of("first"), parsed.actions().stream()
                .map(CopilotActionItem::getType).collect(Collectors.toList()));
    }

    // ==================== 多块与条目级守卫 ====================

    @Test
    void multipleCompleteBlocks_lastCompleteBlockWins() {
        String text = block("{\"actions\":[{\"type\":\"first\"}]}")
                + "中间文本"
                + block("{\"actions\":[{\"type\":\"second\"}]}");

        CopilotStatActionExtractor.Parsed parsed = CopilotStatActionExtractor.parse(text);

        assertEquals("中间文本", parsed.cleanedText());
        assertEquals("second", parsed.actions().get(0).getType());
    }

    @Test
    void entriesMissingType_skipped() {
        String text = block("{\"actions\":[{\"payload\":{}},{\"type\":\" ok \"},{},null]}");

        CopilotStatActionExtractor.Parsed parsed = CopilotStatActionExtractor.parse(text);

        assertEquals(1, parsed.actions().size());
        assertEquals("ok", parsed.actions().get(0).getType());
    }

    @Test
    void nonMapListPayload_fallsBackToEmptyMap() {
        String text = block("[{\"type\":\"t\",\"payload\":\"标量\"},{\"type\":\"t2\",\"payload\":3}]");

        CopilotStatActionExtractor.Parsed parsed = CopilotStatActionExtractor.parse(text);

        assertEquals(Map.of(), parsed.actions().get(0).getPayload());
        assertEquals(Map.of(), parsed.actions().get(1).getPayload());
    }

    @Test
    void actionCount_cappedAtTen() {
        String entries = IntStream.rangeClosed(1, 12)
                .mapToObj(i -> "{\"type\":\"t" + i + "\"}")
                .collect(Collectors.joining(","));
        CopilotStatActionExtractor.Parsed parsed =
                CopilotStatActionExtractor.parse(block("[" + entries + "]"));

        assertEquals(10, parsed.actions().size());
        assertFalse(parsed.actions().stream().anyMatch(i -> "t11".equals(i.getType())));
    }
}
