package com.zzh.stock_calculator.vision.impl;
import com.zzh.stock_calculator.common.BusinessException;
import com.zzh.stock_calculator.vision.OcrExecutor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeTypeUtils;

/**
 * Spring AI ChatClient 多模态调用（唯一 OcrExecutor 实现）。
 * 只负责「图进、文本出」；markdown 清理与 JSON 反序列化在 GeminiTradeVisionServiceImpl。
 */
@Slf4j
@Component
public class GeminiOcrExecutorImpl implements OcrExecutor {

    private final ChatClient chatClient;

    @Autowired
    public GeminiOcrExecutorImpl(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
    }

    @Override
    @Cacheable(value = "genericVisionCache", key = "#cacheKey")
    public String execute(String cacheKey, byte[] imageBytes, String systemPrompt) {
        log.info("未命中视觉缓存，调用多模态大模型解析 (CacheKey={})...", cacheKey);
        return callVisionModel(imageBytes, systemPrompt);
    }

    private String callVisionModel(byte[] imageBytes, String promptText) {
        try {
            ByteArrayResource resource = new ByteArrayResource(imageBytes);

            // 直接通过 fluent API 传入文本和多模态介质
            String content = chatClient.prompt()
                    .user(u -> u.text(promptText)
                            .media(MimeTypeUtils.IMAGE_JPEG, resource))
                    .call()
                    .content();

            if (content == null || content.isBlank()) {
                throw new BusinessException(500, "多模态大模型未返回有效数据");
            }

            return content;

        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("调用多模态视觉模型失败", e);
            throw new BusinessException(500, "图像内容解析异常: " + e.getMessage());
        }
    }


}
