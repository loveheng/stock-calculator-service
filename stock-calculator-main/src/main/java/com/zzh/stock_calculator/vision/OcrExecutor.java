package com.zzh.stock_calculator.vision;
/**
 * 通用视觉模型执行器（带缓存拦截）。
 * 独立成接口的原因：① @Cacheable 缓存边界——必须经代理调用，同类内部直调会失效；
 * ② JSON 反序列化留在调用方（GeminiTradeVisionServiceImpl），接口契约只有「图进、文本出」。
 * 唯一实现：GeminiOcrExecutorImpl（Spring AI ChatClient）；原 native（RestClient）实现已随
 * native 模块删除（2026-08-31）。
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
