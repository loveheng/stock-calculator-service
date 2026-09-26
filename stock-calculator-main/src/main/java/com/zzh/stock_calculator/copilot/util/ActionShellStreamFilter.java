package com.zzh.stock_calculator.copilot.util;

/**
 * 动作外壳流式截断器（动作输出规范⑤，askStream delta 通道专用；有状态、非线程安全——
 * 每个 SSE 请求 new 一个，仅在 subscribe 回调线程内使用）。
 *
 * <p>与 {@link CopilotStatActionExtractor#parse} 的归档口径逐条对齐（流式视图 = 权威全文的增量前缀）：
 * ① 遇完整开标签起截留，壳内内容永不下发；② 闭合后恢复下发壳后正文（parse 保留壳后文本同口径）；
 * ③ 无完整标签时仅扣留尾部「疑似标签前缀」防跨 chunk 拆分漏判，其余照常透传——修复旧「开标签出现
 * 即整段抑制」的两个缺陷：半截标签闪现、开标签所在 chunk 的正文前缀被整条丢弃；④ flush 兜底：
 * 未入壳的扣留文本按正文补发（parse 无完整标签=原文口径），壳内未闭合=丢弃（parse 未闭合残块剔除口径）。</p>
 *
 * <p>纯字符串状态机，零外部依赖；done 事件的 cleanedText 仍是唯一权威全文，
 * 流式尾部经 done 整体替换自愈，本类只保证增量视图不闪烁机器 JSON。</p>
 */
public class ActionShellStreamFilter {

    private final StringBuilder buffer = new StringBuilder();
    private boolean inShell;

    /** 消费一段 LLM 增量，返回本次可安全下发给前端的文本（永不含外壳片段） */
    public String filter(String delta) {
        if (delta == null || delta.isEmpty()) {
            return "";
        }
        if (inShell) {
            buffer.append(delta);
            return drainShell();
        }
        buffer.append(delta);
        String combined = buffer.toString();
        int open = combined.indexOf(CopilotStatActionExtractor.OPEN_TAG);
        if (open >= 0) {
            inShell = true;
            String before = combined.substring(0, open);
            String rest = combined.substring(open + CopilotStatActionExtractor.OPEN_TAG.length());
            buffer.setLength(0);
            buffer.append(rest);
            return before + drainShell();
        }
        int keep = tailPartialMatch(combined, CopilotStatActionExtractor.OPEN_TAG);
        String emit = combined.substring(0, combined.length() - keep);
        buffer.setLength(0);
        buffer.append(combined.substring(combined.length() - keep));
        return emit;
    }

    /**
     * 流结束前调用一次：未入壳时把扣留的疑似标签前缀按正文补发（无完整标签=原文口径）；
     * 壳内未闭合=丢弃（parse 未闭合残块剔除口径）。
     */
    public String flush() {
        String held = inShell ? "" : buffer.toString();
        buffer.setLength(0);
        inShell = false;
        return held;
    }

    /** 壳内：找闭标签；未找到只扣留疑似闭标签前缀的尾巴（壳内其余内容一律不下发）；找到则出壳并恢复壳后正文 */
    private String drainShell() {
        String combined = buffer.toString();
        int close = combined.indexOf(CopilotStatActionExtractor.CLOSE_TAG);
        if (close < 0) {
            int keep = tailPartialMatch(combined, CopilotStatActionExtractor.CLOSE_TAG);
            buffer.setLength(0);
            if (keep > 0) {
                buffer.append(combined.substring(combined.length() - keep));
            }
            return "";
        }
        inShell = false;
        String rest = combined.substring(close + CopilotStatActionExtractor.CLOSE_TAG.length());
        buffer.setLength(0);
        buffer.append(rest);
        return filter("");
    }

    /** text 尾部与 tag 前缀的最长匹配长度（严格小于 tag.length()，完整命中交给 indexOf；0=无） */
    private static int tailPartialMatch(String text, String tag) {
        int max = Math.min(tag.length() - 1, text.length());
        for (int k = max; k > 0; k--) {
            if (text.regionMatches(text.length() - k, tag, 0, k)) {
                return k;
            }
        }
        return 0;
    }
}
