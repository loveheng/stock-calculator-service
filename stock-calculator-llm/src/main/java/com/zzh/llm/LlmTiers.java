package com.zzh.llm;

/**
 * 全局 tier 名常量（唯一事实源）：调用侧一律引用本类，禁止服务内自起别名。
 * <ul>
 *   <li>{@link #MAX}：规划/复杂问答——强推理、贵、可慢；</li>
 *   <li>{@link #CHAT}：常规聊天——实时等待、质量可感知；</li>
 *   <li>{@link #MINI}：提取/摘要/提炼——廉价批量、单次可错可重试。</li>
 * </ul>
 * openai- 前缀标记「OpenAI 兼容协议 chat 端点」，与 CF embedding / gemini 原生
 * vision 划界；渠道换供应商名字不变。
 */
public final class LlmTiers {

    public static final String MAX = "openai-max";
    public static final String CHAT = "openai-chat";
    public static final String MINI = "openai-mini";

    /** embedding 档（ai.embeddings.embed）：供应商可切换（cloudflare | openai） */
    public static final String EMBED = "embed";

    private LlmTiers() {
    }
}
