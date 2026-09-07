package com.zzh.stock_calculator.copilot.util;

import com.zzh.stock_calculator.copilot.dto.CopilotDtos.CopilotActionItem;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * LLM 输出动作块容错提取器（custom_stat 等 taskType 场景，纯函数无状态）。
 *
 * <p>模版约定 LLM 在回复末尾输出一个动作块（格式与 {@link #OPEN_TAG}/{@link #CLOSE_TAG} 一致，
 * data.sql 播种模文与本文常量需同步维护），块内为 {"actions":[{type,payload}...]} JSON 或裸数组。
 * 提取结果只进响应（done 事件 / JSON 信封）的 actions 字段——ephemeral：不落库、不打日志、
 * 不做 payload 语义校验（形状守卫责任在前端 utils/copilotActions 白名单）。</p>
 *
 * <p>容错红线（fail-open，绝不抛错）：无块 / 块内 JSON 非法 / 结构不符 → 返回 null，
 * 聊天文本原样保留（custom-stats-backend-support.md §6：前端守卫静默丢弃是设计内兜底）；
 * 完整块全部从权威全文中剔除（聊天气泡/归档不显示机器 JSON），未闭合的尾部残块同样剔除。</p>
 */
public final class CopilotStatActionExtractor {

    /** 动作块开标签（与 data.sql 播种模文保持一致） */
    public static final String OPEN_TAG = "<copilot-actions>";

    /** 动作块闭标签（与 data.sql 播种模文保持一致） */
    public static final String CLOSE_TAG = "</copilot-actions>";

    /** 单次响应动作数上限（前端白名单上限 5，此处放宽做防御） */
    private static final int MAX_ACTIONS = 10;

    private static final ObjectMapper JSON = new ObjectMapper();

    private CopilotStatActionExtractor() {}

    /** 提取产物：cleanedText = 剔除动作块后的权威全文；actions = 结构化动作（无合法块 = null） */
    public record Parsed(String cleanedText, List<CopilotActionItem> actions) {}

    /**
     * 容错解析：文本不含动作块 → 返回 null（原文即权威全文）；
     * 含块 → 剔除全部块（含未闭合尾部残块），actions 取最后一个完整块（块按约定只出现一次）。
     */
    public static Parsed parse(String fullText) {
        if (fullText == null || fullText.isEmpty()) {
            return null;
        }
        List<CopilotActionItem> actions = null;
        StringBuilder cleaned = new StringBuilder(fullText.length());
        int cursor = 0;
        while (true) {
            int open = fullText.indexOf(OPEN_TAG, cursor);
            if (open < 0) {
                break;
            }
            cleaned.append(fullText, cursor, open);
            int close = fullText.indexOf(CLOSE_TAG, open + OPEN_TAG.length());
            if (close < 0) {
                // 未闭合残块（LLM 截断）：自开标签起恒为机器文本，剔除到结尾即止
                cursor = fullText.length();
                break;
            }
            List<CopilotActionItem> parsed = parseActions(fullText.substring(open + OPEN_TAG.length(), close));
            if (parsed != null) {
                actions = parsed; // 多块时取最后一块
            }
            cursor = close + CLOSE_TAG.length();
        }
        if (cursor == 0) {
            return null; // 全文无任何块（连未闭合残块都没有）：原文原样
        }
        cleaned.append(fullText, cursor, fullText.length());
        String trimmed = cleaned.toString().trim();
        return new Parsed(trimmed, actions);
    }

    /** 块内 JSON → 动作列表；非法/空列表返回 null。接受 {"actions":[...]} 或裸 [...]，条目缺 type 跳过 */
    private static List<CopilotActionItem> parseActions(String inner) {
        try {
            Object root = JSON.readValue(inner.trim(), Object.class);
            Object raw = root instanceof List<?> list ? list
                    : root instanceof Map<?, ?> map ? map.get("actions")
                    : null;
            if (!(raw instanceof List<?> list)) {
                return null;
            }
            List<CopilotActionItem> out = new ArrayList<>();
            for (Object o : list) {
                if (out.size() >= MAX_ACTIONS) {
                    break;
                }
                if (!(o instanceof Map<?, ?> item) || !(item.get("type") instanceof String type) || type.isBlank()) {
                    continue;
                }
                Object payload = item.get("payload");
                out.add(CopilotActionItem.builder()
                        .type(type.trim())
                        .payload(payload instanceof Map<?, ?> || payload instanceof List<?> ? payload : Map.of())
                        .build());
            }
            return out.isEmpty() ? null : out;
        } catch (Exception e) {
            return null;
        }
    }
}
