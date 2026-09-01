package com.zzh.stock_calculator.vision.service;

/**
 * OCR 文本识别策略接口（策略模式）。
 * 每个实现代表一个识别渠道（azure / ocrspace / local-gemini），
 * 由 {@link OcrChainManager} 按预设优先级（实现类的 @Order）编排为责任链。
 *
 * <p>结果契约（严格区分两类结果）：
 * <ul>
 *   <li><b>业务识别为空</b>：图片本身没有文字 → 正常返回 ""（空字符串），调度器视为成功并缓存；</li>
 *   <li><b>网络/限流异常</b>：HTTP 429、5xx、连接/读取超时、鉴权失败等 → 抛出
 *       {@link OcrChannelException}，调度器记录 Warning 后流转下一渠道。</li>
 * </ul>
 */
public interface OcrService {

    /** 渠道名（用于日志与全链失败原因汇总，如 "azure"、"ocrspace"、"local-gemini"） */
    String channelName();

    /** 自我健康检查：渠道关闭或缺少 Key 等配置时返回 false，调度器直接跳过该节点 */
    boolean isAvailable();

    /**
     * 识别图片中的文字
     *
     * @param imageBytes 图片字节数组（调用方已完成前置校验）
     * @param language   语言提示（如 "chs"）；空白时各渠道自行使用默认值或自动检测
     * @return 识别出的文本；图中无文字时返回 ""，绝不返回 null
     * @throws OcrChannelException 渠道不可用 / 网络 / 限流 / 响应异常
     */
    String recognizeText(byte[] imageBytes, String language);
}
