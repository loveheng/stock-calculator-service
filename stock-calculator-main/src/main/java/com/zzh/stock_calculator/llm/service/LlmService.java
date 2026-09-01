package com.zzh.stock_calculator.llm.service;

/**
 * LLM 文本处理策略接口（策略模式）。
 * 每个实现代表一个模型渠道（gemini / groq / fallback），由 {@link com.zzh.stock_calculator.llm.LlmChainRouter}
 * 按预设优先级（实现类的 @Order）编排为责任链。
 *
 * <p>结果契约（与 OCR 侧 OcrService 保持一致）：
 * <ul>
 *   <li>正常返回模型输出文本（trim 后，非 null；内容为空时返回 ""）；</li>
 *   <li>HTTP 429、5xx、连接/读取超时、鉴权失败等 → 抛出 {@link LlmProviderException}，
 *       调度器记录 Warning 后流转下一渠道。</li>
 * </ul>
 */
public interface LlmService {

    /** 渠道名（用于日志与全链失败原因汇总，如 "gemini"、"groq"、"fallback"） */
    String providerName();

    /** 自我健康检查：渠道关闭或缺少 Key / baseUrl 时返回 false，调度器直接跳过该节点 */
    boolean isAvailable();

    /**
     * 发起一次对话补全
     *
     * @param systemPrompt 系统提示词（角色与通用约束）
     * @param userMessage  用户消息（任务指令 + 待处理文本）
     * @return 模型输出文本；内容为空时返回 ""，绝不返回 null
     * @throws LlmProviderException 渠道不可用 / 网络 / 限流 / 响应异常
     */
    String chat(String systemPrompt, String userMessage);
}
