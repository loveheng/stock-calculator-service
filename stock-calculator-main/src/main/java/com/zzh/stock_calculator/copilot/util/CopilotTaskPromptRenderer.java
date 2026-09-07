package com.zzh.stock_calculator.copilot.util;

import tools.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.function.Function;

/**
 * 任务型 Prompt 渲染器（taskType 路由专用，纯函数无状态）。
 *
 * <p>custom_stat（自定义统计代码生成）场景下，系统提示词整段替换为任务模版
 * （DB 标签 {@link #TAG_CUSTOM_STAT_GEN}，含执行契约 + 字段字典 + 输出纪律），
 * 人设/页面快照/数据新鲜度等聊天段不再叠加；渲染仅填充三个运行时占位符：</p>
 * <ul>
 *   <li>{@code {SAMPLE_ROWS}} ← contextSummary.sampleRows（根级或 data 子对象；键缺失时
 *       回退整个 contextSummary 原文——接口契约约定该场景 contextSummary 的内容即样例行，
 *       兼容前端直接下发样例对象/包一层信封两种形态）</li>
 *   <li>{@code {DRAFT_CONTEXT}} ← contextSummary.draftContext（迭代协议：prompt 种子 +
 *       当前 code + 本轮反馈）；缺失 = 首轮生成，填充首轮指令</li>
 *   <li>{@code {USER_CONTENT}} ← 本次提问原文（模版自包含需求锚点）</li>
 * </ul>
 *
 * <p>容错红线：taskType 缺省/未知、模版未配置或读取异常一律返回 null，由编排层回落
 * 既有聊天模版链路（宽松降级不报错，custom-stats-backend-support.md §7.3）；contextSummary
 * 非合法 JSON 视为无该键。全链路不落库不打日志（样例行含用户真实数据）。</p>
 */
public final class CopilotTaskPromptRenderer {

    /** taskType 取值：自定义统计代码生成 */
    public static final String TASK_TYPE_CUSTOM_STAT = "custom_stat";

    /** 自定义统计生成器的模版标签（DB copilot_prompt_template，data.sql 播种） */
    public static final String TAG_CUSTOM_STAT_GEN = "copilot_custom_stat_gen";

    private static final ObjectMapper JSON = new ObjectMapper();

    private CopilotTaskPromptRenderer() {}

    /**
     * 构建任务型系统提示词；返回 null 表示不走任务模版（回落既有聊天链路）。
     *
     * @param taskType       请求声明的任务类型（可空）
     * @param contextSummary ephemeral 快照 JSON（可空，含 sampleRows / draftContext）
     * @param question       本次提问原文（模版内 {USER_CONTENT} 占位符填充值）
     * @param templateReader 模版读取函数（key → content，未命中/异常返回 null），由调用方接 CopilotPromptResolver
     */
    public static String buildCustomStatSystemPrompt(String taskType, String contextSummary,
                                                     String question, Function<String, String> templateReader) {
        if (templateReader == null || !TASK_TYPE_CUSTOM_STAT.equals(trimToNull(taskType))) {
            return null;
        }
        String template;
        try {
            template = templateReader.apply(TAG_CUSTOM_STAT_GEN);
        } catch (Exception e) {
            return null; // 模版读取异常与未配置同等对待：宽松回落
        }
        if (template == null || template.isBlank()) {
            return null;
        }
        String sampleRows = extractContextKey(contextSummary, "sampleRows");
        if (sampleRows == null && contextSummary != null && !contextSummary.isBlank()) {
            // 契约兜底：该场景 contextSummary 的内容即样例行，前端未按键名包装时整段直填
            sampleRows = contextSummary.trim();
        }
        return render(template, sampleRows, extractContextKey(contextSummary, "draftContext"), question);
    }

    /** 占位符渲染：精确字面量替换（无正则），值内容含大括号/占位符样式文本均安全 */
    static String render(String template, String sampleRows, String draftContext, String question) {
        String sample = sampleRows == null ? "（未提供样例行）" : sampleRows;
        String draft = draftContext == null
                ? "首轮生成：无既有代码，直接按用户需求生成。"
                : "迭代轮：必须基于下方当前草稿修改，返回完整替换代码（禁止 diff/patch/省略号）。"
                  + "草稿 JSON 含需求种子 prompt、当前代码 code、本轮反馈 feedback：\n" + draftContext;
        return template
                .replace("{SAMPLE_ROWS}", sample)
                .replace("{DRAFT_CONTEXT}", draft)
                .replace("{USER_CONTENT}", question == null ? "" : question);
    }

    /**
     * 从 contextSummary JSON 提取指定键的 JSON 文本：根级优先，其次 data 子对象
     * （对齐 Copilot 快照信封形态）。键不存在 / 非法 JSON / 结构不符返回 null，绝不抛错。
     */
    static String extractContextKey(String contextSummary, String key) {
        if (contextSummary == null || contextSummary.isBlank()) {
            return null;
        }
        try {
            Object root = JSON.readValue(contextSummary, Object.class);
            if (!(root instanceof Map<?, ?> map)) {
                return null;
            }
            Object value = map.get(key);
            if (value == null && map.get("data") instanceof Map<?, ?> data) {
                value = data.get(key);
            }
            return value == null ? null : JSON.writeValueAsString(value);
        } catch (Exception e) {
            return null;
        }
    }

    private static String trimToNull(String s) {
        return s == null ? null : s.trim();
    }
}
