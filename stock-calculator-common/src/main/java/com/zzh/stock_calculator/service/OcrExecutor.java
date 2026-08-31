package com.zzh.stock_calculator.service;

/**
 * 通用视觉模型执行器（带缓存拦截）。
 * 变体无关：main（Spring AI）/ native（RestClient）各自实现，
 * 接口只约定「图进、文本出」，JSON 反序列化由调用方（GeminiTradeVisionServiceImpl）完成，
 * 避免 Jackson 版本烧进接口契约（阶段二决策）。
 */
public interface OcrExecutor {

    /**
     * @param cacheKey     缓存唯一标识（如图片 MD5）
     * @param imageBytes   预处理后的图片字节数组
     * @param systemPrompt 业务定制的 System / Vision Prompt
     * @return 模型返回的原始文本（未清理 markdown 围栏，由调用方 cleanMarkdown 后解析）
     */
    String execute(String cacheKey, byte[] imageBytes, String systemPrompt);
}
